package com.hubspot.singularity.smtp;

import java.util.concurrent.TimeUnit;

import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hubspot.mesos.JavaUtils;
import com.hubspot.singularity.SingularityAbort;
import com.hubspot.singularity.config.SMTPConfiguration;
import com.hubspot.singularity.data.MetadataManager;
import com.hubspot.singularity.mesos.SingularityMesosSchedulerDelegator;
import com.hubspot.singularity.scheduler.SingularityLeaderOnlyPoller;
import com.hubspot.singularity.sentry.SingularityExceptionNotifier;

@Singleton
public class SingularityMailRecordCleaner extends SingularityLeaderOnlyPoller {

  private static final Logger LOG = LoggerFactory.getLogger(SingularityMailRecordCleaner.class);

  private final MetadataManager metadataManager;
  private final Optional<SMTPConfiguration> smtpConfiguration;

  @Inject
  public SingularityMailRecordCleaner(LeaderLatch leaderLatch, Optional<SMTPConfiguration> smtpConfiguration, SingularityMesosSchedulerDelegator mesosScheduler, MetadataManager metadataManager, SingularityExceptionNotifier exceptionNotifier, SingularityAbort abort) {
    super(leaderLatch, mesosScheduler, exceptionNotifier, abort, smtpConfiguration.isPresent() ? Math.max(smtpConfiguration.get().getRateLimitCooldownMillis(), smtpConfiguration.get().getRateLimitPeriodMillis()) : 0, TimeUnit.MILLISECONDS, SchedulerLockType.NO_LOCK);

    this.metadataManager = metadataManager;
    this.smtpConfiguration = smtpConfiguration;
  }

  @Override
  public void runActionOnPoll() {
    final long start = System.currentTimeMillis();
    final long rateLimitExpiresAfter = Math.max(smtpConfiguration.get().getRateLimitCooldownMillis(), smtpConfiguration.get().getRateLimitPeriodMillis());

    LOG.debug("Cleaning stale mail records");

    int numCleaned = 0;
    int numSeen = 0;

    for (String requestId : metadataManager.getRequestsWithMailRecords()) {
      for (String emailType : metadataManager.getEmailTypesWithMailRecords(requestId)) {
        for (String mailRecordTimestamp : metadataManager.getMailRecords(requestId, emailType)) {
          numSeen++;
          if (start - Long.parseLong(mailRecordTimestamp) > rateLimitExpiresAfter) {
            metadataManager.deleteMailRecord(requestId, emailType, mailRecordTimestamp);
            numCleaned++;
          }
        }
      }
    }

    LOG.debug("Cleaned {} of {} mail record timestamps in {}", numCleaned, numSeen, JavaUtils.duration(start));
  }

}
