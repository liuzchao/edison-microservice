package de.otto.edison.jobs.service;

import de.otto.edison.jobs.domain.JobInfo;
import de.otto.edison.jobs.repository.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.metrics.GaugeService;

import javax.annotation.PostConstruct;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

import static de.otto.edison.jobs.domain.JobInfoBuilder.jobInfoBuilder;
import static de.otto.edison.jobs.service.JobRunner.createAndPersistJobRunner;
import static java.lang.System.currentTimeMillis;
import static java.net.URI.create;
import static java.time.Clock.systemDefaultZone;
import static java.util.Collections.emptyList;
import static java.util.Objects.isNull;
import static java.util.UUID.randomUUID;

/**
 * @author Guido Steinacker
 * @since 15.02.15
 */
public class DefaultJobService implements JobService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultJobService.class);

    @Autowired
    private JobRepository repository;
    @Autowired
    private ScheduledExecutorService executor;
    @Autowired
    private GaugeService gaugeService;
    @Autowired(required = false)
    private List<JobRunnable> jobRunnables = emptyList();


    @Value("${server.contextPath}")
    private String serverContextPath;
    private final Clock clock;

    public DefaultJobService() {
        this.clock = systemDefaultZone();
    }

    public DefaultJobService(final String serverContextPath,
                      final JobRepository jobRepository,
                      final List<JobRunnable> jobRunnables,
                      final GaugeService gaugeService,
                      final Clock clock,
                      final ScheduledExecutorService executor) {
        this.serverContextPath = serverContextPath;
        this.repository = jobRepository;
        this.jobRunnables = jobRunnables;
        this.gaugeService = gaugeService;
        this.clock = clock;
        this.executor = executor;
    }

    @PostConstruct
    public void postConstruct() {
        LOG.info("Found {} JobRunnables: {}", +jobRunnables.size(), jobRunnables.stream().map(JobRunnable::getJobType).collect(Collectors.toList()));
    }

    @Override
    public URI startAsyncJob(String jobType) {
        final Optional<JobRunnable> jobRunnable = jobRunnables.stream().filter((r) -> r.getJobType().equalsIgnoreCase(jobType)).findFirst();
        return startAsyncJob(jobRunnable.orElseThrow(() -> new IllegalArgumentException("No JobRunnable for" + jobType)));
    }

    @Override
    public URI startAsyncJob(final JobRunnable jobRunnable) {
        final JobInfo alreadyRunning = repository.findRunningJobByType(jobRunnable.getJobType());
        if (isNull(alreadyRunning)) {
            return startAsync(metered(jobRunnable));
        } else {
            LOG.info("Job {} triggered but not started - still running.", alreadyRunning.getJobUri());
            return alreadyRunning.getJobUri();
        }
    }

    @Override
    public Optional<JobInfo> findJob(final URI uri) {
        return repository.findBy(uri);
    }

    @Override
    public List<JobInfo> findJobs(final Optional<String> type, final int count) {
        if (type.isPresent()) {
            return repository.findLatestBy(type.get(), count);
        } else {
            return repository.findLatest(count);
        }
    }

    @Override
    public void deleteJobs(final Optional<String> type) {
        if (type.isPresent()) {
            repository.findByType(type.get()).forEach((j)-> repository.removeIfStopped(j.getJobUri()));
        } else {
            repository.findAll().forEach((j)-> repository.removeIfStopped(j.getJobUri()));
        }
    }

    private URI startAsync(final JobRunnable jobRunnable) {
        final JobInfo jobInfo = jobInfoBuilder(jobRunnable.getJobType(), newJobUri()).build();
        JobRunner jobRunner = createAndPersistJobRunner(jobInfo, repository, clock, executor);
        executor.execute(() -> jobRunner.start(jobRunnable));
        return jobInfo.getJobUri();
    }

    private JobRunnable metered(final JobRunnable delegate) {
        return new JobRunnable() {
            @Override
            public String getJobType() {
                return delegate.getJobType();
            }

            @Override
            public void execute(final JobLogger logger) {
                long ts = currentTimeMillis();
                delegate.execute(logger);
                gaugeService.submit(gaugeName(), (currentTimeMillis()-ts)/1000L);
            }

            private String gaugeName() {
                return "gauge.jobs.runtime." + delegate.getJobType().toLowerCase();
            }
        };
    }

    private URI newJobUri() {
        return create(serverContextPath + "/internal/jobs/" + randomUUID());
    }
}
