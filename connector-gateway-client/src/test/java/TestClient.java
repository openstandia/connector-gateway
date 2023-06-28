import com.evolveum.polygon.connector.csv.CsvConfiguration;
import com.evolveum.polygon.connector.csv.CsvConnector;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.api.*;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.Schema;
import org.identityconnectors.framework.common.objects.filter.Filter;
import org.identityconnectors.framework.impl.api.APIConfigurationImpl;
import org.identityconnectors.framework.impl.api.local.LocalConnectorInfoImpl;
import org.identityconnectors.framework.impl.api.remote.RemoteConnectorInfoImpl;
import org.identityconnectors.test.common.TestHelpers;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TestClient {

    public static void main(String[] args) {
        new TestClient().test();
    }

    protected CsvConfiguration newConfiguration() {
        CsvConfiguration conf = new CsvConfiguration();
        conf.setFilePath(new File("./users.csv"));
        conf.setFieldDelimiter(",");
        conf.setUniqueAttribute("id");
        conf.setNameAttribute("name");
        return conf;
    }

    protected RemoteFrameworkConnectionInfo newRemoteFrameworkConnectionInfo(String host, int port) {
        RemoteFrameworkConnectionInfo remoteFramewrorkInfo = new RemoteFrameworkConnectionInfo(host, port,
                new GuardedString("changeit".toCharArray()), false, null, 0);
        return remoteFramewrorkInfo;
    }

    protected ConnectorFacade newFacade(CsvConfiguration conf) {
        ConnectorInfoManagerFactory connectorInfoManagerFactory = ConnectorInfoManagerFactory.getInstance();
        ConnectorInfoManager remoteInfoManager = connectorInfoManagerFactory.getRemoteManager(newRemoteFrameworkConnectionInfo("localhost", 8759));
        List<ConnectorInfo> connectorInfos = remoteInfoManager.getConnectorInfos();
        for (ConnectorInfo connectorInfo : connectorInfos) {
            System.out.println(connectorInfo);
        }

        ConnectorFacadeFactory factory = ConnectorFacadeFactory.getInstance();
        APIConfiguration impl = TestHelpers.createTestConfiguration(CsvConnector.class, conf);
        LocalConnectorInfoImpl info = (LocalConnectorInfoImpl) ((APIConfigurationImpl) impl).getConnectorInfo();
        RemoteConnectorInfoImpl remoteInfo = info.toRemote();
        RemoteFrameworkConnectionInfo connInfo = new RemoteFrameworkConnectionInfo("localhost", 8759, new GuardedString("changeit".toCharArray()));
        remoteInfo.setRemoteConnectionInfo(connInfo);
        ConnectorKey connectorKey = new ConnectorKey("com.evolveum.polygon.connector-csv", "2.4", "com.evolveum.polygon.connector.csv.CsvConnector");
        remoteInfo.setConnectorKey(connectorKey);
        ((APIConfigurationImpl) impl).setConnectorInfo(remoteInfo);

        impl.getResultsHandlerConfiguration().setEnableAttributesToGetSearchResultsHandler(false);
        impl.getResultsHandlerConfiguration().setEnableNormalizingResultsHandler(false);
        impl.getResultsHandlerConfiguration().setEnableFilteredResultsHandler(false);
        return factory.newInstance(impl);
    }

    //    @Test
    void test() {
        ConnectorFacade conn = newFacade(newConfiguration());

//        ExecutorService service = Executors.newFixedThreadPool(10);
//
//        for (int i =0; i < 1; i++) {
//            service.submit(() -> {
//                ConnectorFacade conn = newFacade(newConfiguration());
//
//                long total = 0;
//                int count = 0;
//                while (true) {
//                    long start = System.currentTimeMillis();
//                    try {
//
////                        conn.search(ObjectClass.ACCOUNT, null, (connectorObject) -> {
////                            System.out.println(connectorObject);
////                            return true;
////                        }, null);
//                        conn.test();
//
//                    } catch (RuntimeException e) {
//                        e.printStackTrace();
//                    }
//
//                    count++;
//                    long end = System.currentTimeMillis();
//                    total += (end - start);
//                    System.out.println("Average of search: " + (total / count) + ", Count: " + count);
//                }
//            });
//        }
//        service.submit(() -> {
//            long total = 0;
//            int count = 0;
//            while (true) {
//                long start = System.currentTimeMillis();
//                Schema schema = conn.schema();
//                count++;
//                long end = System.currentTimeMillis();
//                total += (end - start);
//                System.out.println("Average of schema: " + (total / count));
//            }
//        });
//        conn.schema();
//
        conn.search(ObjectClass.ACCOUNT, null, (connectorObject) -> {
            System.out.println(connectorObject);
            return true;
        }, null);
    }
}
