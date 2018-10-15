package org.graylog.aws.inputs.transports;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.codahale.metrics.MetricSet;
import com.google.common.collect.Maps;
import com.google.common.eventbus.EventBus;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.assistedinject.Assisted;
import okhttp3.HttpUrl;
import org.graylog.aws.auth.AWSAuthProvider;
import org.graylog.aws.config.AWSPluginConfiguration;
import org.graylog.aws.kinesis.KinesisConsumer;
import org.graylog2.plugin.LocalMetricRegistry;
import org.graylog2.plugin.cluster.ClusterConfigService;
import org.graylog2.plugin.configuration.Configuration;
import org.graylog2.plugin.configuration.ConfigurationRequest;
import org.graylog2.plugin.configuration.fields.ConfigurationField;
import org.graylog2.plugin.configuration.fields.DropdownField;
import org.graylog2.plugin.configuration.fields.TextField;
import org.graylog2.plugin.inputs.MessageInput;
import org.graylog2.plugin.inputs.MisfireException;
import org.graylog2.plugin.inputs.annotations.ConfigClass;
import org.graylog2.plugin.inputs.annotations.FactoryClass;
import org.graylog2.plugin.inputs.codecs.CodecAggregator;
import org.graylog2.plugin.inputs.transports.ThrottleableTransport;
import org.graylog2.plugin.inputs.transports.Transport;
import org.graylog2.plugin.journal.RawMessage;
import org.graylog2.plugin.system.NodeId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class KinesisTransport extends ThrottleableTransport {
    private static final Logger LOG = LoggerFactory.getLogger(KinesisTransport.class);
    public static final String NAME = "awskinesis";

    private static final String CK_AWS_REGION = "aws_region";
    private static final String CK_ACCESS_KEY = "aws_access_key";
    private static final String CK_SECRET_KEY = "aws_secret_key";
    private static final String CK_ASSUME_ROLE_ARN = "aws_assume_role_arn";
    private static final String CK_KINESIS_STREAM_NAME = "kinesis_stream_name";

    private final Configuration configuration;
    private final org.graylog2.Configuration graylogConfiguration;
    private final NodeId nodeId;
    private final LocalMetricRegistry localRegistry;
    private final ClusterConfigService clusterConfigService;

    private KinesisConsumer reader;
    ExecutorService kinesisExecutorService = null;
    Future<?> kinesisExecutorFuture = null;

    /**
     * Indicates if the Kinesis consumer has been stopped due to throttling. Allows the consumer to be restarted
     * once throttling is cleared.
     */
    public AtomicBoolean stoppedDueToThrottling = new AtomicBoolean(false);

    @Inject
    public KinesisTransport(@Assisted final Configuration configuration,
                            EventBus serverEventBus,
                            org.graylog2.Configuration graylogConfiguration,
                            final ClusterConfigService clusterConfigService,
                            final NodeId nodeId,
                            LocalMetricRegistry localRegistry) {
        super(serverEventBus, configuration);
        this.clusterConfigService = clusterConfigService;
        this.configuration = configuration;
        this.graylogConfiguration = graylogConfiguration;
        this.nodeId = nodeId;
        this.localRegistry = localRegistry;
    }

    /**
     * Called after the Kinesis consumer executor thread terminates. If the thread was terminated due to throttling,
     * it will restart automatically once throttling has been cleared.
     */
    public void requestRestartWhenUnthrottled() {

        /* The Atomic boolean {@code stoppedDueToThrottling} is only true when the Kinesis consumer has been stopped
         * due to being throttled. */
        if (stoppedDueToThrottling.get()) {
            LOG.info("[throttled] The Kinesis consumer is currently in a throttled state, so the consumer has been " +
                     "temporarily stopped. Once unthrottled, the consumer will be restarted and message processing " +
                     "will begin again.");
            blockUntilUnthrottled();

            LOG.info("[unthrottled] Restarting Kinesis consumer.");
            stoppedDueToThrottling.set(true);
            kinesisExecutorService.submit(KinesisTransport.this.reader);
        }
    }

    @Override
    public void doLaunch(MessageInput input) throws MisfireException {

        final AWSPluginConfiguration awsConfig = clusterConfigService.getOrDefault(AWSPluginConfiguration.class,
                                                                                   AWSPluginConfiguration.createDefault());
        AWSAuthProvider authProvider = new AWSAuthProvider(
                awsConfig, configuration.getString(CK_ACCESS_KEY),
                configuration.getString(CK_SECRET_KEY),
                configuration.getString(CK_AWS_REGION),
                configuration.getString(CK_ASSUME_ROLE_ARN)
        );

        this.reader = new KinesisConsumer(
                configuration.getString(CK_KINESIS_STREAM_NAME),
                Region.getRegion(Regions.fromName(configuration.getString(CK_AWS_REGION))),
                kinesisCallback(input),
                awsConfig,
                authProvider,
                nodeId,
                graylogConfiguration.getHttpProxyUri() == null ? null : HttpUrl.get(graylogConfiguration.getHttpProxyUri()),
                this);

        LOG.info("Starting Kinesis reader thread for input [{}/{}]", input.getName(), input.getId());

        kinesisExecutorService = getExecutorService();
        kinesisExecutorFuture = kinesisExecutorService.submit(this.reader);
    }

    private ExecutorService getExecutorService() {
        return Executors.newSingleThreadExecutor(new ThreadFactoryBuilder()
                                                         .setDaemon(true)
                                                         .setNameFormat("aws-kinesis-reader-%d")
                                                         .setUncaughtExceptionHandler((t, e) -> LOG.error("Uncaught exception in AWS Kinesis reader.", e))
                                                         .build());
    }

    private Consumer<byte[]> kinesisCallback(final MessageInput input) {
        return (data) -> input.processRawMessage(new RawMessage(data));
    }

    @Override
    public void doStop() {
        if (this.reader != null) {
            this.reader.stop();
        }
    }

    @Override
    public void setMessageAggregator(CodecAggregator aggregator) {
        // Not supported.
    }

    @Override
    public MetricSet getMetricSet() {
        return localRegistry;
    }

    @FactoryClass
    public interface Factory extends Transport.Factory<KinesisTransport> {
        @Override
        KinesisTransport create(Configuration configuration);

        @Override
        Config getConfig();
    }

    @ConfigClass
    public static class Config extends ThrottleableTransport.Config {
        @Override
        public ConfigurationRequest getRequestedConfiguration() {
            final ConfigurationRequest r = super.getRequestedConfiguration();

            Map<String, String> regions = Maps.newHashMap();
            for (Regions region : Regions.values()) {
                regions.put(region.getName(), region.toString());
            }

            r.addField(new DropdownField(
                    CK_AWS_REGION,
                    "AWS Region",
                    Regions.US_EAST_1.getName(),
                    regions,
                    "The AWS region the Kinesis stream is running in.",
                    ConfigurationField.Optional.NOT_OPTIONAL
            ));

            r.addField(new TextField(
                    CK_ACCESS_KEY,
                    "AWS access key",
                    "",
                    "Access key of an AWS user with sufficient permissions. (See documentation)",
                    ConfigurationField.Optional.OPTIONAL
            ));

            r.addField(new TextField(
                    CK_SECRET_KEY,
                    "AWS secret key",
                    "",
                    "Secret key of an AWS user with sufficient permissions. (See documentation)",
                    ConfigurationField.Optional.OPTIONAL,
                    TextField.Attribute.IS_PASSWORD
            ));

            r.addField(new TextField(
                    CK_ASSUME_ROLE_ARN,
                    "AWS assume role ARN",
                    "",
                    "Role ARN with required permissions (cross account access)",
                    ConfigurationField.Optional.OPTIONAL
            ));

            r.addField(new TextField(
                    CK_KINESIS_STREAM_NAME,
                    "Kinesis Stream name",
                    "",
                    "The name of the Kinesis stream that receives your messages. See README for instructions on how to connect messages to a Kinesis Stream.",
                    ConfigurationField.Optional.NOT_OPTIONAL
            ));

            return r;
        }
    }
}
