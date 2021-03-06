/*
 * The MIT License
 *
 * Copyright (c) 2004-, Kohsuke Kawaguchi, Sun Microsystems, Inc., and a number of other of contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package hudson.plugins.ec2;

import com.amazonaws.AmazonClientException;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.*;
import com.amazonaws.internal.StaticCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsImpl;
import com.cloudbees.jenkins.plugins.awscredentials.AmazonWebServicesCredentials;
import com.cloudbees.plugins.credentials.*;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.Domain;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.ProxyConfiguration;
import hudson.model.*;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.*;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletException;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URL;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;

/**
 * Hudson's view of EC2.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class EC2Cloud extends Cloud {

    private static final Logger LOGGER = Logger.getLogger(EC2Cloud.class.getName());

    public static final String DEFAULT_EC2_HOST = "us-east-1";

    public static final String DEFAULT_EC2_ENDPOINT = "http://ec2.amazonaws.com";

    public static final String AWS_URL_HOST = "amazonaws.com";

    public static final String EC2_SLAVE_TYPE_SPOT = "spot";

    public static final String EC2_SLAVE_TYPE_DEMAND = "demand";

    private static final SimpleFormatter sf = new SimpleFormatter();

    private transient ReentrantLock slaveCountingLock = new ReentrantLock();

    private final boolean useInstanceProfileForCredentials;

    private final String roleArn;

    private final String roleSessionName;

    /**
     * Id of the {@link AmazonWebServicesCredentials} used to connect to Amazon ECS
     */
    @CheckForNull
    private String credentialsId;
    @CheckForNull
    @Deprecated
    private transient String accessId;
    @CheckForNull
    @Deprecated
    private transient Secret secretKey;

    protected final EC2PrivateKey privateKey;

    /**
     * Upper bound on how many instances we may provision.
     */
    public final int instanceCap;

    private final List<? extends SlaveTemplate> templates;

    private transient KeyPair usableKeyPair;

    protected transient AmazonEC2 connection;

    private static AWSCredentialsProvider awsCredentialsProvider;

    protected EC2Cloud(String id, boolean useInstanceProfileForCredentials, String credentialsId, String privateKey,
            String instanceCapStr, List<? extends SlaveTemplate> templates, String roleArn, String roleSessionName) {
        super(id);
        this.useInstanceProfileForCredentials = useInstanceProfileForCredentials;
        this.roleArn = roleArn;
        this.roleSessionName = roleSessionName;
        this.credentialsId = credentialsId;
        this.privateKey = new EC2PrivateKey(privateKey);

        if (templates == null) {
            this.templates = Collections.emptyList();
        } else {
            this.templates = templates;
        }

        if (instanceCapStr.isEmpty()) {
            this.instanceCap = Integer.MAX_VALUE;
        } else {
            this.instanceCap = Integer.parseInt(instanceCapStr);
        }

        readResolve(); // set parents
    }

    public abstract URL getEc2EndpointUrl() throws IOException;

    public abstract URL getS3EndpointUrl() throws IOException;

    protected Object readResolve() {
        this.slaveCountingLock = new ReentrantLock();
        for (SlaveTemplate t : templates)
            t.parent = this;
        if (this.accessId != null && this.secretKey != null && credentialsId == null) {
            String secretKeyEncryptedValue = this.secretKey.getEncryptedValue();
            // REPLACE this.accessId and this.secretId by a credential

            SystemCredentialsProvider systemCredentialsProvider = SystemCredentialsProvider.getInstance();
            // ITERATE ON EXISTING CREDS AND DON'T CREATE IF EXIST
            for (Credentials credentials: systemCredentialsProvider.getCredentials()) {
                if (credentials instanceof AmazonWebServicesCredentials) {
                    AmazonWebServicesCredentials awsCreds = (AmazonWebServicesCredentials) credentials;
                    AWSCredentials awsCredentials = awsCreds.getCredentials();
                    if (accessId.equals(awsCredentials.getAWSAccessKeyId()) &&
                            Secret.toString(this.secretKey).equals(awsCredentials.getAWSSecretKey())) {

                        this.credentialsId = awsCreds.getId();
                        this.accessId = null;
                        this.secretKey = null;
                        return this;
                    }
                }
            }
            // CREATE
            for (CredentialsStore credentialsStore: CredentialsProvider.lookupStores(Jenkins.getInstance())) {

                if (credentialsStore instanceof  SystemCredentialsProvider.StoreImpl) {

                    try {
                        String credsId = UUID.randomUUID().toString();
                        credentialsStore.addCredentials(Domain.global(), new AWSCredentialsImpl(
                                CredentialsScope.SYSTEM, credsId, this.accessId, secretKeyEncryptedValue,
                                "EC2 Cloud - " + getDisplayName()));
                        this.credentialsId = credsId;
                        this.accessId = null;
                        this.secretKey = null;
                        return this;
                    } catch (IOException e) {
                        this.credentialsId = null;
                        LOGGER.log(Level.WARNING, "Exception converting legacy configuration to the new credentials API", e);
                    }
                }

            }
            // PROBLEM, GLOBAL STORE NOT FOUND
            LOGGER.log(Level.WARNING, "EC2 Plugin could not migrate credentials to the Jenkins Global Credentials Store, EC2 Plugin for cloud {0} must be manually reconfigured", getDisplayName());
        }
        return this;
    }

    public boolean isUseInstanceProfileForCredentials() {
        return useInstanceProfileForCredentials;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public String getRoleSessionName() {
        return roleSessionName;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public EC2PrivateKey getPrivateKey() {
        return privateKey;
    }

    public String getInstanceCapStr() {
        if (instanceCap == Integer.MAX_VALUE)
            return "";
        else
            return String.valueOf(instanceCap);
    }

    public List<SlaveTemplate> getTemplates() {
        return Collections.unmodifiableList(templates);
    }

    public SlaveTemplate getTemplate(String template) {
        for (SlaveTemplate t : templates) {
            if (t.description.equals(template)) {
                return t;
            }
        }
        return null;
    }

    /**
     * Gets {@link SlaveTemplate} that has the matching {@link Label}.
     */
    public SlaveTemplate getTemplate(Label label) {
        for (SlaveTemplate t : templates) {
            if (t.getMode() == Node.Mode.NORMAL) {
                if (label == null || label.matches(t.getLabelSet())) {
                    return t;
                }
            } else if (t.getMode() == Node.Mode.EXCLUSIVE) {
                if (label != null && label.matches(t.getLabelSet())) {
                    return t;
                }
            }
        }
        return null;
    }

    /**
     * Gets the {@link KeyPairInfo} used for the launch.
     */
    public synchronized KeyPair getKeyPair() throws AmazonClientException, IOException {
        if (usableKeyPair == null)
            usableKeyPair = privateKey.find(connect());
        return usableKeyPair;
    }

    /**
     * Debug command to attach to a running instance.
     */
    public void doAttach(StaplerRequest req, StaplerResponse rsp, @QueryParameter String id)
            throws ServletException, IOException, AmazonClientException {
        checkPermission(PROVISION);
        SlaveTemplate t = getTemplates().get(0);

        StringWriter sw = new StringWriter();
        StreamTaskListener listener = new StreamTaskListener(sw);
        EC2AbstractSlave node = t.attach(id, listener, countCurrentEC2Slaves(t));
        Jenkins.getInstance().addNode(node);

        rsp.sendRedirect2(req.getContextPath() + "/computer/" + node.getNodeName());
    }

    public HttpResponse doProvision(@QueryParameter String template) throws ServletException, IOException {
        checkPermission(PROVISION);
        if (template == null) {
            throw HttpResponses.error(SC_BAD_REQUEST, "The 'template' query parameter is missing");
        }
        SlaveTemplate t = getTemplate(template);
        if (t == null) {
            throw HttpResponses.error(SC_BAD_REQUEST, "No such template: " + template);
        }

        try {
            List<EC2AbstractSlave> nodes = getNewOrExistingAvailableSlave(t, 1, true);
            if (nodes == null || nodes.isEmpty())
                throw HttpResponses.error(SC_BAD_REQUEST, "Cloud or AMI instance cap would be exceeded for: " + template);

            //Reconnect a stopped instance, the ADD is invoking the connect only for the node creation
            Computer c = nodes.get(0).toComputer();
            if (nodes.get(0).getStopOnTerminate() && c !=  null) {
                c.connect(false);
            }
            Jenkins.getInstance().addNode(nodes.get(0));

            return HttpResponses.redirectViaContextPath("/computer/" + nodes.get(0).getNodeName());
        } catch (AmazonClientException e) {
            throw HttpResponses.error(SC_INTERNAL_SERVER_ERROR, e);
        }
    }

    /**
     * Counts the number of instances in EC2 that can be used with the specified image and a template. Also removes any
     * nodes associated with canceled requests.
     *
     * @param template If left null, then all instances are counted.
     */
    private int countCurrentEC2Slaves(SlaveTemplate template) throws AmazonClientException {
        String jenkinsServerUrl = null;
        JenkinsLocationConfiguration jenkinsLocation = JenkinsLocationConfiguration.get();
        if (jenkinsLocation != null)
            jenkinsServerUrl = jenkinsLocation.getUrl();

        if (jenkinsServerUrl == null) {
            LOGGER.log(Level.WARNING, "No Jenkins server URL specified, it is strongly recommended to open /configure and set the server URL. " +
                    "Not having has disabled the per-master instance cap counting (cf. https://github.com/jenkinsci/ec2-plugin/pull/310)");
        }

        LOGGER.log(Level.FINE, "Counting current slaves: "
            + (template != null ? (" AMI: " + template.getAmi() + " TemplateDesc: " + template.description) : " All AMIS")
            + " Jenkins Server: " + jenkinsServerUrl);
        int n = 0;
        Set<String> instanceIds = new HashSet<String>();
        String description = template != null ? template.description : null;

        //FIXME convert to a filter query
        for (Reservation r : connect().describeInstances().getReservations()) {
            for (Instance i : r.getInstances()) {
                if (isEc2ProvisionedAmiSlave(i.getTags(), description)
                    && isEc2ProvisionedJenkinsSlave(i.getTags(), jenkinsServerUrl)
                    && (template == null || template.getAmi().equals(i.getImageId()))) {
                    InstanceStateName stateName = InstanceStateName.fromValue(i.getState().getName());
                    if (stateName != InstanceStateName.Terminated && 
                        stateName != InstanceStateName.ShuttingDown && 
                        stateName != InstanceStateName.Stopped ) {
                        LOGGER.log(Level.FINE, "Existing instance found: " + i.getInstanceId() + " AMI: " + i.getImageId()
                        + (template != null ? (" Template: " + description) : "") + " Jenkins Server: " + jenkinsServerUrl);
                        n++;
                        instanceIds.add(i.getInstanceId());
                    }
                }
            }
        }
        List<SpotInstanceRequest> sirs = null;
        List<Filter> filters = new ArrayList<Filter>();
        List<String> values;
        if (template != null) {
            values = new ArrayList<String>();
            values.add(template.getAmi());
            filters.add(new Filter("launch.image-id", values));
        }

        if(jenkinsServerUrl!=null) {
        // The instances must match the jenkins server url
            filters.add(new Filter("tag:" + EC2Tag.TAG_NAME_JENKINS_SERVER_URL + "=" + jenkinsServerUrl));
        }

        values = new ArrayList<String>();
        values.add(EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE);
        filters.add(new Filter("tag-key", values));

        DescribeSpotInstanceRequestsRequest dsir = new DescribeSpotInstanceRequestsRequest().withFilters(filters);
        try {
            sirs = connect().describeSpotInstanceRequests(dsir).getSpotInstanceRequests();
        } catch (Exception ex) {
            // Some ec2 implementations don't implement spot requests (Eucalyptus)
            LOGGER.log(Level.FINEST, "Describe spot instance requests failed", ex);
        }
        Set<SpotInstanceRequest> sirSet = new HashSet<>();

        if (sirs != null) {
            for (SpotInstanceRequest sir : sirs) {
                sirSet.add(sir);
                if (sir.getState().equals("open") || sir.getState().equals("active")) {
                    if (sir.getInstanceId() != null && instanceIds.contains(sir.getInstanceId()))
                        continue;

                    if (isEc2ProvisionedAmiSlave(sir.getTags(), description)) {
                        LOGGER.log(Level.FINE, "Spot instance request found: " + sir.getSpotInstanceRequestId() + " AMI: "
                                + sir.getInstanceId() + " state: " + sir.getState() + " status: " + sir.getStatus());

                        n++;
                        if (sir.getInstanceId() != null)
                            instanceIds.add(sir.getInstanceId());
                    }
                } else {
                    // Canceled or otherwise dead
                    for (Node node : Jenkins.getInstance().getNodes()) {
                        try {
                            if (!(node instanceof EC2SpotSlave))
                                continue;
                            EC2SpotSlave ec2Slave = (EC2SpotSlave) node;
                            if (ec2Slave.getSpotInstanceRequestId().equals(sir.getSpotInstanceRequestId())) {
                                LOGGER.log(Level.INFO, "Removing dead request: " + sir.getSpotInstanceRequestId() + " AMI: "
                                        + sir.getInstanceId() + " state: " + sir.getState() + " status: " + sir.getStatus());
                                Jenkins.getInstance().removeNode(node);
                                break;
                            }
                        } catch (IOException e) {
                            LOGGER.log(Level.WARNING, "Failed to remove node for dead request: " + sir.getSpotInstanceRequestId()
                                            + " AMI: " + sir.getInstanceId() + " state: " + sir.getState() + " status: " + sir.getStatus(),
                                    e);
                        }
                    }
                }
            }
        }

        // Count nodes where the spot request does not yet exist (sometimes it takes time for the request to appear
        // in the EC2 API)
        for (Node node : Jenkins.getInstance().getNodes()) {
            if (!(node instanceof EC2SpotSlave))
                continue;
            EC2SpotSlave ec2Slave = (EC2SpotSlave) node;
            SpotInstanceRequest sir = ec2Slave.getSpotRequest();

            if (sir == null) {
                LOGGER.log(Level.FINE, "Found spot node without request: " + ec2Slave.getSpotInstanceRequestId());
                n++;
                continue;
            }

            if (sirSet.contains(sir))
                continue;

            sirSet.add(sir);

            if (sir.getState().equals("open") || sir.getState().equals("active")) {
                if (template != null) {
                    List<Tag> instanceTags = sir.getTags();
                    for (Tag tag : instanceTags) {
                        if (StringUtils.equals(tag.getKey(), EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE) && StringUtils.equals(tag.getValue(), getSlaveTypeTagValue(EC2_SLAVE_TYPE_SPOT, template.description)) && sir.getLaunchSpecification().getImageId().equals(template.getAmi())) {
                        
                            if (sir.getInstanceId() != null && instanceIds.contains(sir.getInstanceId()))
                                continue;
                
                            LOGGER.log(Level.FINE, "Spot instance request found (from node): " + sir.getSpotInstanceRequestId() + " AMI: "
                                    + sir.getInstanceId() + " state: " + sir.getState() + " status: " + sir.getStatus());
                            n++;
                            
                            if (sir.getInstanceId() != null)
                                instanceIds.add(sir.getInstanceId());
                        }
                    }
                }
            }
        }

        return n;
    }

    private boolean isEc2ProvisionedJenkinsSlave(List<Tag> tags, String serverUrl) {
        for (Tag tag : tags) {
            if (StringUtils.equals(tag.getKey(), EC2Tag.TAG_NAME_JENKINS_SERVER_URL)) {
                return StringUtils.equals(tag.getValue(), serverUrl);
            }
        }
        return (serverUrl == null);
    }

    private boolean isEc2ProvisionedAmiSlave(List<Tag> tags, String description) {
        for (Tag tag : tags) {
            if (StringUtils.equals(tag.getKey(), EC2Tag.TAG_NAME_JENKINS_SLAVE_TYPE)) {
                if (description == null) {
                    return true;
                } else if (StringUtils.equals(tag.getValue(), EC2Cloud.EC2_SLAVE_TYPE_DEMAND)
                        || StringUtils.equals(tag.getValue(), EC2Cloud.EC2_SLAVE_TYPE_SPOT)) {
                    // To handle cases where description is null and also upgrade cases for existing slave nodes.
                    return true;
                } else if (StringUtils.equals(tag.getValue(), getSlaveTypeTagValue(EC2Cloud.EC2_SLAVE_TYPE_DEMAND, description))
                        || StringUtils.equals(tag.getValue(), getSlaveTypeTagValue(EC2Cloud.EC2_SLAVE_TYPE_SPOT, description))) {
                    return true;
                } else {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * Returns the maximum number of possible slaves that can be created.
     */
    private int getPossibleNewSlavesCount(SlaveTemplate template) throws AmazonClientException {
        int estimatedTotalSlaves = countCurrentEC2Slaves(null);
        int estimatedAmiSlaves = countCurrentEC2Slaves(template);

        int availableTotalSlaves = instanceCap - estimatedTotalSlaves;
        int availableAmiSlaves = template.getInstanceCap() - estimatedAmiSlaves;
        LOGGER.log(Level.FINE, "Available Total Slaves: " + availableTotalSlaves + " Available AMI slaves: " + availableAmiSlaves
                + " AMI: " + template.getAmi() + " TemplateDesc: " + template.description);

        return Math.min(availableAmiSlaves, availableTotalSlaves);
    }

    /**
     * Obtains a slave whose AMI matches the AMI of the given template, and that also has requiredLabel (if requiredLabel is non-null)
     * forceCreateNew specifies that the creation of a new slave is required. Otherwise, an existing matching slave may be re-used
     */
    private List<EC2AbstractSlave> getNewOrExistingAvailableSlave(SlaveTemplate t, int number, boolean forceCreateNew) {
        try {
            slaveCountingLock.lock();
            int possibleSlavesCount = getPossibleNewSlavesCount(t);
            int currSlaveCount = countCurrentEC2Slaves(t);

            if (possibleSlavesCount <= 0) {
                LOGGER.log(Level.INFO, "{0}. Cannot provision - no capacity for instances: " + possibleSlavesCount, t);
                return null;
            }

            try {
                EnumSet<SlaveTemplate.ProvisionOptions> provisionOptions;
                if (forceCreateNew)
                    provisionOptions = EnumSet.of(SlaveTemplate.ProvisionOptions.FORCE_CREATE);
                else
                    provisionOptions = EnumSet.of(SlaveTemplate.ProvisionOptions.ALLOW_CREATE);

                if (number > possibleSlavesCount) {
                    LOGGER.log(Level.INFO, String.format("%d nodes were requested for the template %s, " +
                            "but because of instance cap only %d can be provisioned", number, t, possibleSlavesCount));
                    number = possibleSlavesCount;
                }

                return t.provision(number, provisionOptions, currSlaveCount);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, t + ". Exception during provisioning", e);
                return null;
            }
        } finally { slaveCountingLock.unlock(); }
    }

    @Override
    public Collection<PlannedNode> provision(final Label label, int excessWorkload) {
        final SlaveTemplate t = getTemplate(label);
        List<PlannedNode> plannedNodes = new ArrayList<>();

        try {
            LOGGER.log(Level.INFO, "{0}. Attempting to provision slave needed by excess workload of " + excessWorkload + " units", t);
            int number = Math.max(excessWorkload / t.getNumExecutors(), 1);
            final List<EC2AbstractSlave> slaves = getNewOrExistingAvailableSlave(t, number, false);

            if (slaves == null || slaves.isEmpty()) {
                LOGGER.warning("Can't raise nodes for " + t);
                return Collections.emptyList();
            }

            for (final EC2AbstractSlave slave : slaves) {
                if (slave == null) {
                    LOGGER.warning("Can't raise node for " + t);
                    continue;
                }

                plannedNodes.add(createPlannedNode(t, slave));
                excessWorkload -= t.getNumExecutors();
            }

            LOGGER.log(Level.INFO, "{0}. Attempting provision finished, excess workload: " + excessWorkload, t);
            LOGGER.log(Level.INFO, "We have now {0} computers, waiting for {1} more",
                    new Object[]{Jenkins.getInstance().getComputers().length, plannedNodes.size()});
            return plannedNodes;
        } catch (AmazonClientException e) {
            LOGGER.log(Level.WARNING, t + ". Exception during provisioning", e);
            return Collections.emptyList();
        }
    }

    private PlannedNode createPlannedNode(final SlaveTemplate t, final EC2AbstractSlave slave) {
        return new PlannedNode(t.getDisplayName(),
                Computer.threadPoolForRemoting.submit(new Callable<Node>() {
                    public Node call() throws Exception {
                        while (true) {
                            String instanceId = slave.getInstanceId();
                            if (slave instanceof EC2SpotSlave) {
                                if (((EC2SpotSlave) slave).isSpotRequestDead()) {
                                    LOGGER.log(Level.WARNING, "{0} Spot request died, can't do anything. Terminate provisioning", t);
                                    return null;
                                }

                                // Spot Instance does not have instance id yet.
                                if (StringUtils.isEmpty(instanceId)) {
                                    Thread.sleep(5000);
                                    continue;
                                }
                            }

                            Instance instance = CloudHelper.getInstanceWithRetry(instanceId, slave.getCloud());
                            if (instance == null) {
                                LOGGER.log(Level.WARNING, "{0} Can't find instance with instance id `{1}` in cloud {2}. Terminate provisioning ",
                                        new Object[]{t, instanceId, slave.cloudName});
                                return null;
                            }

                            InstanceStateName state = InstanceStateName.fromValue(instance.getState().getName());
                            if (state.equals(InstanceStateName.Running))  {
                                //Spot instance are not reconnected automatically,
                                // but could be new orphans that has the option enable
                                Computer c = slave.toComputer();
                                if (slave.getStopOnTerminate() && (c != null ))  {
                                    c.connect(false);
                                }
                                
                                long startTime = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - instance.getLaunchTime().getTime());
                                LOGGER.log(Level.INFO, "{0} Node {1} moved to RUNNING state in {2} seconds and is ready to be connected by Jenkins",
                                        new Object[]{t, slave.getNodeName(), startTime});
                                return slave;
                            }

                            if (!state.equals(InstanceStateName.Pending)) {
                                LOGGER.log(Level.WARNING, "{0}. Node {1} is neither pending, neither running, it's {2}. Terminate provisioning",
                                        new Object[]{t, state, slave.getNodeName()});
                                return null;
                            }

                            Thread.sleep(5000);
                        }
                    }
                })
                , t.getNumExecutors());
    }


    @Override
    public boolean canProvision(Label label) {
        return getTemplate(label) != null;
    }

    protected AWSCredentialsProvider createCredentialsProvider() {
        return createCredentialsProvider(useInstanceProfileForCredentials, credentialsId);
    }

    public static String getSlaveTypeTagValue(String slaveType, String templateDescription) {
        return templateDescription != null ? slaveType + "_" + templateDescription : slaveType;
    }

    public static AWSCredentialsProvider createCredentialsProvider(final boolean useInstanceProfileForCredentials, final String credentialsId) {
        if (useInstanceProfileForCredentials) {
            return new InstanceProfileCredentialsProvider();
        } else if (StringUtils.isBlank(credentialsId)) {
            return new DefaultAWSCredentialsProviderChain();
        } else {
            AmazonWebServicesCredentials credentials = getCredentials(credentialsId);
            if (credentials != null)
                return new StaticCredentialsProvider(credentials.getCredentials());
        }
        return new DefaultAWSCredentialsProviderChain();
    }

    public static AWSCredentialsProvider createCredentialsProvider(
            final boolean useInstanceProfileForCredentials,
            final String credentialsId,
            final String roleArn,
            final String roleSessionName,
            final String region) {

        AWSCredentialsProvider provider = createCredentialsProvider(useInstanceProfileForCredentials, credentialsId);

        if (StringUtils.isNotEmpty(roleArn) && StringUtils.isNotEmpty(roleSessionName)) {
            return new STSAssumeRoleSessionCredentialsProvider.Builder(roleArn, roleSessionName)
                    .withStsClient(AWSSecurityTokenServiceClientBuilder.standard()
                            .withCredentials(provider)
                            .withRegion(region)
                            .withClientConfiguration(createClientConfiguration(convertHostName(region)))
                            .build())
                    .build();
        }

        return provider;
    }

    @CheckForNull
    private static AmazonWebServicesCredentials getCredentials(@Nullable String credentialsId) {
        if (StringUtils.isBlank(credentialsId)) {
            return null;
        }
        return (AmazonWebServicesCredentials) CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(AmazonWebServicesCredentials.class, Jenkins.getInstance(),
                        ACL.SYSTEM, Collections.emptyList()),
                CredentialsMatchers.withId(credentialsId));
    }

    /**
     * Connects to EC2 and returns {@link AmazonEC2}, which can then be used to communicate with EC2.
     */
    public AmazonEC2 connect() throws AmazonClientException {
        try {
            if (connection == null) {
                connection = connect(createCredentialsProvider(), getEc2EndpointUrl());
            }
            return connection;
        } catch (IOException e) {
            throw new AmazonClientException("Failed to retrieve the endpoint", e);
        }
    }

    /***
     * Connect to an EC2 instance.
     *
     * @return {@link AmazonEC2} client
     */
    public synchronized static AmazonEC2 connect(AWSCredentialsProvider credentialsProvider, URL endpoint) {
        awsCredentialsProvider = credentialsProvider;
        AmazonEC2 client = new AmazonEC2Client(credentialsProvider, createClientConfiguration(endpoint.getHost()));
        client.setEndpoint(endpoint.toString());
        return client;
    }

    public static ClientConfiguration createClientConfiguration(final String host) {
        ClientConfiguration config = new ClientConfiguration();
        config.setMaxErrorRetry(16); // Default retry limit (3) is low and often
        // cause problems. Raise it a bit.
        // See: https://issues.jenkins-ci.org/browse/JENKINS-26800
        config.setSignerOverride("AWS4SignerType");
        ProxyConfiguration proxyConfig = Jenkins.getInstance().proxy;
        Proxy proxy = proxyConfig == null ? Proxy.NO_PROXY : proxyConfig.createProxy(host);
        if (!proxy.equals(Proxy.NO_PROXY) && proxy.address() instanceof InetSocketAddress) {
            InetSocketAddress address = (InetSocketAddress) proxy.address();
            config.setProxyHost(address.getHostName());
            config.setProxyPort(address.getPort());
            if (null != proxyConfig.getUserName()) {
                config.setProxyUsername(proxyConfig.getUserName());
                config.setProxyPassword(proxyConfig.getPassword());
            }
        }
        return config;
    }

    /***
     * Convert a configured hostname like 'us-east-1' to a FQDN or ip address
     */
    public static String convertHostName(String ec2HostName) {
        if (ec2HostName == null || ec2HostName.length() == 0)
            ec2HostName = DEFAULT_EC2_HOST;
        if (!ec2HostName.contains("."))
            ec2HostName = "ec2." + ec2HostName + "." + AWS_URL_HOST;
        return ec2HostName;
    }

    /***
     * Convert a user entered string into a port number "" -&gt; -1 to indicate default based on SSL setting
     */
    public static Integer convertPort(String ec2Port) {
        if (ec2Port == null || ec2Port.length() == 0)
            return -1;
        return Integer.parseInt(ec2Port);
    }

    /**
     * Computes the presigned URL for the given S3 resource.
     *
     * @param path String like "/bucketName/folder/folder/abc.txt" that represents the resource to request.
     */
    public URL buildPresignedURL(String path) throws AmazonClientException {
        AWSCredentials credentials = awsCredentialsProvider.getCredentials();
        long expires = System.currentTimeMillis() + 60 * 60 * 1000;
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(path, credentials.getAWSSecretKey());
        request.setExpiration(new Date(expires));
        AmazonS3 s3 = new AmazonS3Client(credentials);
        return s3.generatePresignedUrl(request);
    }

    /* Parse a url or return a sensible error */
    public static URL checkEndPoint(String url) throws FormValidation {
        try {
            return new URL(url);
        } catch (MalformedURLException ex) {
            throw FormValidation.error("Endpoint URL is not a valid URL");
        }
    }

    public static abstract class DescriptorImpl extends Descriptor<Cloud> {

        public InstanceType[] getInstanceTypes() {
            return InstanceType.values();
        }

        public FormValidation doCheckUseInstanceProfileForCredentials(@QueryParameter boolean value) {
            if (value) {
                try {
                    new InstanceProfileCredentialsProvider().getCredentials();
                } catch (AmazonClientException e) {
                    return FormValidation.error(Messages.EC2Cloud_FailedToObtainCredentialsFromEC2(), e.getMessage());
                }
            }

            return FormValidation.ok();
        }

        public FormValidation doCheckPrivateKey(@QueryParameter String value) throws IOException, ServletException {
            boolean hasStart = false, hasEnd = false;
            BufferedReader br = new BufferedReader(new StringReader(value));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.equals("-----BEGIN RSA PRIVATE KEY-----"))
                    hasStart = true;
                if (line.equals("-----END RSA PRIVATE KEY-----"))
                    hasEnd = true;
            }
            if (!hasStart)
                return FormValidation.error("This doesn't look like a private key at all");
            if (!hasEnd)
                return FormValidation
                        .error("The private key is missing the trailing 'END RSA PRIVATE KEY' marker. Copy&paste error?");
            return FormValidation.ok();
        }

        protected FormValidation doTestConnection(URL ec2endpoint, boolean useInstanceProfileForCredentials, String credentialsId, String privateKey, String roleArn, String roleSessionName, String region)
                throws IOException, ServletException {
            try {
                AWSCredentialsProvider credentialsProvider = createCredentialsProvider(useInstanceProfileForCredentials, credentialsId, roleArn, roleSessionName, region);
                AmazonEC2 ec2 = connect(credentialsProvider, ec2endpoint);
                ec2.describeInstances();

                if (privateKey == null)
                    return FormValidation.error("Private key is not specified. Click 'Generate Key' to generate one.");

                if (privateKey.trim().length() > 0) {
                    // check if this key exists
                    EC2PrivateKey pk = new EC2PrivateKey(privateKey);
                    if (pk.find(ec2) == null)
                        return FormValidation
                                .error("The EC2 key pair private key isn't registered to this EC2 region (fingerprint is "
                                        + pk.getFingerprint() + ")");
                }

                return FormValidation.ok(Messages.EC2Cloud_Success());
            } catch (AmazonClientException e) {
                LOGGER.log(Level.WARNING, "Failed to check EC2 credential", e);
                return FormValidation.error(e.getMessage());
            }
        }

        public FormValidation doGenerateKey(StaplerResponse rsp, URL ec2EndpointUrl, boolean useInstanceProfileForCredentials, String credentialsId, String roleArn, String roleSessionName, String region)
                throws IOException, ServletException {
            try {
                AWSCredentialsProvider credentialsProvider = createCredentialsProvider(useInstanceProfileForCredentials, credentialsId, roleArn, roleSessionName, region);
                AmazonEC2 ec2 = connect(credentialsProvider, ec2EndpointUrl);
                List<KeyPairInfo> existingKeys = ec2.describeKeyPairs().getKeyPairs();

                int n = 0;
                while (true) {
                    boolean found = false;
                    for (KeyPairInfo k : existingKeys) {
                        if (k.getKeyName().equals("hudson-" + n))
                            found = true;
                    }
                    if (!found)
                        break;
                    n++;
                }

                CreateKeyPairRequest request = new CreateKeyPairRequest("hudson-" + n);
                KeyPair key = ec2.createKeyPair(request).getKeyPair();

                rsp.addHeader("script",
                        "findPreviousFormItem(button,'privateKey').value='" + key.getKeyMaterial().replace("\n", "\\n") + "'");

                return FormValidation.ok(Messages.EC2Cloud_Success());
            } catch (AmazonClientException e) {
                LOGGER.log(Level.WARNING, "Failed to check EC2 credential", e);
                return FormValidation.error(e.getMessage());
            }
        }

        public ListBoxModel doFillCredentialsIdItems() {
            return new StandardListBoxModel()
                    .withEmptySelection()
                    .withMatching(
                            CredentialsMatchers.always(),
                            CredentialsProvider.lookupCredentials(AmazonWebServicesCredentials.class,
                                    Jenkins.getInstance(),
                                    ACL.SYSTEM,
                                    Collections.emptyList()));
        }
    }

    public static void log(Logger logger, Level level, TaskListener listener, String message) {
        log(logger, level, listener, message, null);
    }

    public static void log(Logger logger, Level level, TaskListener listener, String message, Throwable exception) {
        logger.log(level, message, exception);
        if (listener != null) {
            if (exception != null)
                message += " Exception: " + exception;
            LogRecord lr = new LogRecord(level, message);
            lr.setLoggerName(LOGGER.getName());
            PrintStream printStream = listener.getLogger();
            printStream.print(sf.format(lr));
        }
    }

}
