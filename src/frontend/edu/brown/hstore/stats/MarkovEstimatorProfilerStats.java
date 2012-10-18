package edu.brown.hstore.stats;

import java.util.ArrayList;
import java.util.Iterator;

import org.apache.log4j.Logger;
import org.voltdb.StatsSource;
import org.voltdb.SysProcSelector;
import org.voltdb.VoltTable;
import org.voltdb.VoltTable.ColumnInfo;
import org.voltdb.VoltType;

import edu.brown.hstore.HStoreSite;
import edu.brown.hstore.estimators.MarkovEstimator;
import edu.brown.hstore.estimators.TransactionEstimator;
import edu.brown.logging.LoggerUtil;
import edu.brown.logging.LoggerUtil.LoggerBoolean;
import edu.brown.profilers.MarkovEstimatorProfiler;
import edu.brown.profilers.ProfileMeasurement;
import edu.brown.utils.CollectionUtil;
import edu.brown.utils.PartitionSet;

public class MarkovEstimatorProfilerStats extends StatsSource {
    private static final Logger LOG = Logger.getLogger(MarkovEstimatorProfilerStats.class);
    private static final LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());
    private static final LoggerBoolean trace = new LoggerBoolean(LOG.isTraceEnabled());
    static {
        LoggerUtil.attachObserver(LOG, debug, trace);
    }

    private final HStoreSite hstore_site;
    private final PartitionSet onePartition;

    public MarkovEstimatorProfilerStats(HStoreSite hstore_site) {
        super(SysProcSelector.MARKOVPROFILER.name(), false);
        this.hstore_site = hstore_site;
        this.onePartition = new PartitionSet(CollectionUtil.first(hstore_site.getLocalPartitionIds()));
    }
    
    @Override
    protected Iterator<Object> getStatsRowKeyIterator(boolean interval) {
        final Iterator<Integer> it = this.onePartition.iterator();
        return new Iterator<Object>() {
            @Override
            public boolean hasNext() {
                return it.hasNext();
            }
            @Override
            public Object next() {
                return it.next();
            }
            @Override
            public void remove() {
                it.remove();
            }
        };
    }

    @Override
    protected void populateColumnSchema(ArrayList<ColumnInfo> columns) {
        super.populateColumnSchema(columns);
        
        // Make a dummy profiler just so that we can get the fields from it
        MarkovEstimatorProfiler profiler = new MarkovEstimatorProfiler();
        assert(profiler != null);
        
        for (ProfileMeasurement pm : profiler.getProfileMeasurements()) {
            String name = pm.getType().toUpperCase();
            // We need two columns per ProfileMeasurement
            //  (1) The total think time in nanoseconds
            //  (2) The number of invocations
            columns.add(new VoltTable.ColumnInfo(name, VoltType.BIGINT));
            columns.add(new VoltTable.ColumnInfo(name+"_CNT", VoltType.BIGINT));
        } // FOR
    }

    @Override
    protected synchronized void updateStatsRow(Object rowKey, Object[] rowValues) {
        Integer partition = (Integer)rowKey;
        TransactionEstimator est = hstore_site.getPartitionExecutor(partition).getTransactionEstimator();
        MarkovEstimatorProfiler profiler = ((MarkovEstimator)est).getProfiler();
        assert(profiler != null);
        
        int offset = this.first_stats_col;
        for (ProfileMeasurement pm : profiler.getProfileMeasurements()) {
            rowValues[offset++] = pm.getTotalThinkTime();
            rowValues[offset++] = pm.getInvocations();
        } // FOR
        super.updateStatsRow(rowKey, rowValues);
    }
}
