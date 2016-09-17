package in.tazj.k8s.letsencrypt.acme;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.route53.AmazonRoute53;
import com.amazonaws.services.route53.AmazonRoute53Client;
import com.amazonaws.services.route53.model.Change;
import com.amazonaws.services.route53.model.ChangeAction;
import com.amazonaws.services.route53.model.ChangeBatch;
import com.amazonaws.services.route53.model.ChangeResourceRecordSetsRequest;
import com.amazonaws.services.route53.model.HostedZone;
import com.amazonaws.services.route53.model.RRType;
import com.amazonaws.services.route53.model.ResourceRecord;
import com.amazonaws.services.route53.model.ResourceRecordSet;

import java.util.Optional;

import in.tazj.k8s.letsencrypt.util.LetsencryptException;
import lombok.extern.slf4j.Slf4j;

/**
 * An ACME challenge responder using AWS Route53.
 */
@Slf4j
public class Route53Responder implements DnsResponder {
  final AmazonRoute53 route53 = new AmazonRoute53Client(new DefaultAWSCredentialsProviderChain());

  @Override
  public String addChallengeRecord(String recordName, String challengeDigest) {
    final Optional<HostedZone> hostedZone = findHostedZone(recordName);

    if (hostedZone.isPresent()) {
      final String zoneId = hostedZone.get().getId();
      final String recordValue = formatChallengeValue(challengeDigest);
      log.info("Creating record {} in zone {} with value {}",
          recordName, hostedZone.get().getName(), recordValue);
      final ResourceRecordSet recordSet = new ResourceRecordSet(recordName, RRType.TXT)
          .withTTL(60L)
          .withResourceRecords(new ResourceRecord(recordValue));
      final ChangeBatch changeBatch = new ChangeBatch(ImmutableList.of(
          new Change(ChangeAction.UPSERT, recordSet)));

      route53.changeResourceRecordSets(new ChangeResourceRecordSetsRequest(zoneId, changeBatch));
      return hostedZone.get().getName();
    } else {
      log.error("No hosted zone found for record: {}!", recordName);
      throw new LetsencryptException("No hosted zone found");
    }
  }

  /** If no hosted zone is supplied explicitly this function will attempt to find a managed zone in
   * AWS that matches the supplied record.
   * If multiple matching zones are found the most specific one is chosen. */
  @VisibleForTesting
  public Optional<HostedZone> findHostedZone(String record) {
    // Determine whether the requested domain has a suffixed full stop, and otherwise add it as the
    // hosted zones returned by Route53 will always contain it.
    final String fqdnRecord;
    if (record.endsWith(".")) {
      fqdnRecord = record;
    } else {
      fqdnRecord = record + ".";
    }

    // Attempt to find the correct hosted zone by matching the longest matching suffix.
    final Optional<HostedZone> matchingZone = route53.listHostedZones().getHostedZones().stream()
        .filter(zone -> fqdnRecord.endsWith(zone.getName()))
        .reduce(((acc, zone) -> {
          if (zone.getName().length() > acc.getName().length()) {
            return zone;
          }
          return acc;
        }));

    matchingZone.ifPresent(zone ->
        log.info("Found matching zone {} for record {}", zone.getName(), record));

    return matchingZone;
  }

  // Route53 requires TXT record values to be explicitly enclosed in quotation marks.
  private static String formatChallengeValue(String challenge) {
    if (challenge.endsWith("\"")) {
      return challenge;
    } else {
      return "\"" + challenge + "\"";
    }
  }
}
