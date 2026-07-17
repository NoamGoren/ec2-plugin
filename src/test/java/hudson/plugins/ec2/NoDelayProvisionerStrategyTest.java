package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import hudson.ExtensionList;
import hudson.model.Label;
import hudson.model.LoadStatistics;
import hudson.plugins.ec2.util.SSHCredentialHelper;
import hudson.slaves.Cloud;
import hudson.slaves.CloudProvisioningListener;
import hudson.slaves.NodeProvisioner;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Verifies that {@link NoDelayProvisionerStrategy} notifies {@link CloudProvisioningListener}s when it provisions.
 *
 * <p>The strategy is registered ahead of Jenkins core's {@code NodeProvisioner.StandardStrategy} and short-circuits
 * the strategy chain, so {@code StandardStrategy} (which fires {@code CloudProvisioningListener.onStarted}) never runs.
 * Unless this strategy fires the listeners itself, extensions that rely on that callback -- for example agent usage
 * trackers -- never observe agents provisioned on demand for a pipeline or label when no-delay provisioning is enabled.
 */
@WithJenkins
class NoDelayProvisionerStrategyTest {

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    @Test
    void firesCloudProvisioningListenerOnStarted() throws Exception {
        SSHCredentialHelper.assureSshCredentialAvailableThroughCredentialProviders("ghi");
        SlaveTemplate template = MockEC2Computer.createSlaveTemplate();
        EC2Cloud cloud = new EC2Cloud(
                "test-cloud",
                true,
                "abc",
                "us-east-1",
                null,
                "ghi",
                "10",
                Collections.singletonList(template),
                null,
                null);
        cloud.setNoDelayProvisioning(true);
        r.jenkins.clouds.add(cloud);

        RecordingListener listener = new RecordingListener();
        ExtensionList.lookup(CloudProvisioningListener.class).add(0, listener);

        Label label = r.jenkins.getLabel(template.getLabelString());

        // One queued task and no capacity: the strategy must provision one agent from the no-delay cloud.
        NodeProvisioner.StrategyDecision decision = new NoDelayProvisionerStrategy().apply(strategyState(label, 1));

        assertEquals(
                NodeProvisioner.StrategyDecision.PROVISIONING_COMPLETED,
                decision,
                "the no-delay strategy should satisfy the demand and complete provisioning");
        assertEquals(1, listener.onStartedCalls.size(), "onStarted must be fired exactly once for the provisioning");

        Call call = listener.onStartedCalls.get(0);
        assertSame(cloud, call.cloud, "onStarted must report the provisioning cloud");
        assertSame(label, call.label, "onStarted must report the requested label");
        assertFalse(call.plannedNodes.isEmpty(), "onStarted must report the planned nodes the cloud returned");
    }

    /**
     * Builds a {@code NodeProvisioner.StrategyState} with the given queue demand and no available capacity. Its only
     * constructor is not public in Jenkins core, so it is reached reflectively; the real {@link NodeProvisioner} for
     * the label supplies the (empty) planned-capacity snapshot.
     */
    private NodeProvisioner.StrategyState strategyState(Label label, int queueLength) throws Exception {
        LoadStatistics.LoadStatisticsSnapshot snapshot = LoadStatistics.LoadStatisticsSnapshot.builder()
                .withQueueLength(queueLength)
                .build();
        Constructor<NodeProvisioner.StrategyState> ctor = NodeProvisioner.StrategyState.class.getDeclaredConstructor(
                NodeProvisioner.class, LoadStatistics.LoadStatisticsSnapshot.class, Label.class, int.class);
        ctor.setAccessible(true);
        return ctor.newInstance(label.nodeProvisioner, snapshot, label, 0);
    }

    private static final class RecordingListener extends CloudProvisioningListener {
        private final List<Call> onStartedCalls = new ArrayList<>();

        @Override
        public void onStarted(Cloud cloud, Label label, Collection<NodeProvisioner.PlannedNode> plannedNodes) {
            onStartedCalls.add(new Call(cloud, label, new ArrayList<>(plannedNodes)));
        }
    }

    private static final class Call {
        private final Cloud cloud;
        private final Label label;
        private final Collection<NodeProvisioner.PlannedNode> plannedNodes;

        Call(Cloud cloud, Label label, Collection<NodeProvisioner.PlannedNode> plannedNodes) {
            this.cloud = cloud;
            this.label = label;
            this.plannedNodes = plannedNodes;
        }
    }
}
