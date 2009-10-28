/*
 * Copyright 2009 LinkedIn, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package voldemort.utils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.junit.Test;

import voldemort.utils.impl.RemoteTestSummarizer;
import voldemort.utils.impl.RsyncDeployer;
import voldemort.utils.impl.SshClusterStarter;
import voldemort.utils.impl.SshClusterStopper;
import voldemort.utils.impl.SshRemoteTest;
import voldemort.utils.impl.TypicaEc2Connection;

public class SmokeTest {

    @Test
    public void test() throws Exception {
        Map<String, String> dnsNames = getInstances();

        if(dnsNames.size() < 3) {
            createInstances(6);
            dnsNames = getInstances();
        }

        final Map<String, Integer> nodeIds = generateClusterDescriptor(dnsNames,
                                                                       "/home/kirk/voldemortdev/voldemort/config/single_node_cluster/config/cluster.xml");
        final Collection<String> hostNames = dnsNames.keySet();
        final String hostUserId = "root";
        final File sshPrivateKey = new File("/home/kirk/Dropbox/Configuration/AWS/id_rsa-mustardgrain-keypair");
        final String voldemortParentDirectory = ".";
        final String voldemortRootDirectory = "voldemort";
        final String voldemortHomeDirectory = "voldemort/config/single_node_cluster";
        final File sourceDirectory = new File("/home/kirk/voldemortdev/voldemort");

        Map<String, String> remoteTestArguments = new HashMap<String, String>();
        final String bootstrapUrl = dnsNames.values().iterator().next();
        int startKeyIndex = 0;
        final int numRequests = 100000;
        final int iterations = 25;

        for(String publicHostName: dnsNames.keySet()) {
            remoteTestArguments.put(publicHostName, "-wd --start-key-index "
                                                    + (startKeyIndex * numRequests)
                                                    + " --value-size 100 --iterations "
                                                    + iterations + " tcp://" + bootstrapUrl
                                                    + ":6666 test " + numRequests);
            startKeyIndex++;
        }

        try {
            new SshClusterStopper(hostNames, sshPrivateKey, hostUserId, voldemortRootDirectory).execute();
        } catch(Exception e) {
            // Ignore...
        }

        new RsyncDeployer(hostNames,
                          sshPrivateKey,
                          sourceDirectory,
                          hostUserId,
                          voldemortParentDirectory).execute();

        new Thread(new Runnable() {

            public void run() {
                try {
                    new SshClusterStarter(hostNames,
                                          sshPrivateKey,
                                          hostUserId,
                                          voldemortRootDirectory,
                                          voldemortHomeDirectory,
                                          nodeIds).execute();
                } catch(RemoteOperationException e) {
                    e.printStackTrace();
                }
            }

        }).start();

        Thread.sleep(5000);

        List<RemoteTestResult> remoteTestResults = new SshRemoteTest(hostNames,
                                                                     sshPrivateKey,
                                                                     hostUserId,
                                                                     voldemortRootDirectory,
                                                                     voldemortHomeDirectory,
                                                                     remoteTestArguments).execute();
        new RemoteTestSummarizer().outputTestResults(remoteTestResults);

        new SshClusterStopper(hostNames, sshPrivateKey, hostUserId, voldemortRootDirectory).execute();
    }

    private Map<String, String> createInstances(int count) throws Exception {
        String accessId = System.getProperty("ec2AccessId");
        String secretKey = System.getProperty("ec2SecretKey");
        String ami = System.getProperty("ec2Ami");
        String keyPairId = System.getProperty("ec2KeyPairId");
        Ec2Connection ec2 = new TypicaEc2Connection(accessId, secretKey);
        return ec2.create(ami, keyPairId, null, count);
    }

    private Map<String, String> getInstances() throws Exception {
        String accessId = System.getProperty("ec2AccessId");
        String secretKey = System.getProperty("ec2SecretKey");
        Ec2Connection ec2 = new TypicaEc2Connection(accessId, secretKey);
        return ec2.list();
    }

    private Map<String, Integer> generateClusterDescriptor(Map<String, String> hostNames,
                                                           String path) throws Exception {
        ClusterGenerator clusterGenerator = new ClusterGenerator();
        List<ClusterNodeDescriptor> nodes = clusterGenerator.createClusterNodeDescriptors(new ArrayList<String>(hostNames.values()),
                                                                                          3);
        String clusterXml = clusterGenerator.createClusterDescriptor("test", nodes);
        FileUtils.writeStringToFile(new File(path), clusterXml);
        Map<String, Integer> nodeIds = new HashMap<String, Integer>();

        for(ClusterNodeDescriptor node: nodes) {
            String privateDnsName = node.getHostName();

            // OK, yeah, super-inefficient...
            for(Map.Entry<String, String> entry: hostNames.entrySet()) {
                if(entry.getValue().equals(privateDnsName)) {
                    nodeIds.put(entry.getKey(), node.getId());
                }
            }
        }

        return nodeIds;
    }

}
