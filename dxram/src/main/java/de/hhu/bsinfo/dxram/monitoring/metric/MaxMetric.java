package de.hhu.bsinfo.dxram.monitoring.metric;

import de.hhu.bsinfo.dxram.monitoring.MonitoringDataStructure;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * This helper class provides methods to calculate a single DataStructure from multiple ones using the maximum.
 *
 * @author Burak Akguel, burak.akguel@hhu.de, 08.06.2018
 */
public class MaxMetric extends Metric {

    public static MonitoringDataStructure calculate(final short p_ownNid, ArrayList<MonitoringDataStructure> p_datas) {
        float[][] floatTable = createFloatTable(p_datas);
        long[][] longTable = createLongTable(p_datas);

        int numData = p_datas.size();
        for (int i = 0; i < floatTable.length; i++) {
            Arrays.sort(floatTable[i]); // maybe just search for max in a loop
            floatTable[i][0] = floatTable[i][numData - 1];
        }
        for (int i = 0; i < longTable.length; i++) {
            Arrays.sort(longTable[i]);
            longTable[i][0] = longTable[i][numData - 1];
        }

        MonitoringDataStructure dataStructure = new MonitoringDataStructure(p_ownNid, System.nanoTime());
        dataStructure.setCpuUsage(floatTable[0][0]);
        dataStructure.setCpuLoads(new float[]{floatTable[1][0], floatTable[2][0], floatTable[3][0]});
        dataStructure.setMemoryUsage(floatTable[4][0]);
        dataStructure.setNetworsStats(new float[]{floatTable[5][0], floatTable[6][0], floatTable[7][0], floatTable[8][0]});
        dataStructure.setDiskStats(new float[]{floatTable[9][0], floatTable[10][0]});
        dataStructure.setJvmMemStats(new float[]{floatTable[11][0], floatTable[12][0], floatTable[13][0], floatTable[14][0]});
        dataStructure.setJvmThreadsStats(new long[]{longTable[0][0], longTable[1][0], longTable[2][0], longTable[3][0]});

        return dataStructure;
    }

}
