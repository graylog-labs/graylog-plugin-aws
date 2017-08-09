package org.graylog.aws.plugin;

import org.graylog.aws.inputs.cloudtrail.CloudTrailCodec;
import org.graylog.aws.inputs.cloudtrail.CloudTrailInput;
import org.graylog.aws.inputs.cloudtrail.CloudTrailTransport;
import org.graylog.aws.inputs.codecs.CloudWatchFlowLogCodec;
import org.graylog.aws.inputs.flowlogs.FlowLogsInput;
import org.graylog.aws.inputs.transports.KinesisTransport;
import org.graylog.aws.processors.instancelookup.AWSInstanceNameLookupProcessor;
import org.graylog2.plugin.PluginModule;

public class AWSModule extends PluginModule {
    @Override
    protected void configure() {
        // CloudTrail
        addCodec(CloudTrailCodec.NAME, CloudTrailCodec.class);
        addTransport(CloudTrailTransport.NAME, CloudTrailTransport.class);
        addMessageInput(CloudTrailInput.class);

        // CloudWatch
        addCodec(CloudWatchFlowLogCodec.NAME, CloudWatchFlowLogCodec.class);
        addTransport(KinesisTransport.NAME, KinesisTransport.class);
        addMessageInput(FlowLogsInput.class);

        // Instance name lookup
        addMessageProcessor(AWSInstanceNameLookupProcessor.class, AWSInstanceNameLookupProcessor.Descriptor.class);
    }
}
