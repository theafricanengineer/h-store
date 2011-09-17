package edu.mit.hstore;

import java.lang.reflect.Field;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.apache.commons.collections15.map.ListOrderedMap;
import org.apache.commons.pool.ObjectPool;
import org.apache.commons.pool.impl.StackObjectPool;
import org.apache.log4j.Logger;
import org.voltdb.BatchPlanner;
import org.voltdb.ExecutionSite;
import org.voltdb.catalog.Database;
import org.voltdb.catalog.Procedure;

import edu.brown.markov.TransactionEstimator;
import edu.brown.statistics.Histogram;
import edu.brown.utils.CollectionUtil;
import edu.brown.utils.CountingPoolableObjectFactory;
import edu.brown.utils.LoggerUtil;
import edu.brown.utils.ProfileMeasurement;
import edu.brown.utils.StringUtil;
import edu.brown.utils.TableUtil;
import edu.brown.utils.LoggerUtil.LoggerBoolean;
import edu.mit.hstore.dtxn.DependencyInfo;
import edu.mit.hstore.dtxn.LocalTransactionState;
import edu.mit.hstore.dtxn.TransactionProfile;
import edu.mit.hstore.dtxn.TransactionState;
import edu.mit.hstore.interfaces.Shutdownable;

/**
 * 
 * @author pavlo
 */
public class HStoreSiteStatus implements Runnable, Shutdownable {
    private static final Logger LOG = Logger.getLogger(HStoreSiteStatus.class);
    private final static LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());
    private final static LoggerBoolean trace = new LoggerBoolean(LOG.isTraceEnabled());
    static {
        LoggerUtil.attachObserver(LOG, debug, trace);
    }
    
    private static final String POOL_FORMAT = "Active:%-5d / Idle:%-5d / Created:%-5d / Destroyed:%-5d / Passivated:%-7d";
    
    
    private static final Pattern THREAD_REGEX = Pattern.compile("(edu\\.brown|edu\\.mit|org\\.voltdb)");
    

    private static final Set<TxnCounter> TXNINFO_COL_DELIMITERS = new HashSet<TxnCounter>();
    private static final Set<TxnCounter> TXNINFO_ALWAYS_SHOW = new HashSet<TxnCounter>();
    private static final Set<TxnCounter> TXNINFO_EXCLUDES = new HashSet<TxnCounter>();
    static {
        CollectionUtil.addAll(TXNINFO_COL_DELIMITERS, TxnCounter.EXECUTED,
                                                      TxnCounter.MULTI_PARTITION,
                                                      TxnCounter.MISPREDICTED);
        CollectionUtil.addAll(TXNINFO_ALWAYS_SHOW,    TxnCounter.MULTI_PARTITION,
                                                      TxnCounter.SINGLE_PARTITION,
                                                      TxnCounter.MISPREDICTED);
        CollectionUtil.addAll(TXNINFO_EXCLUDES,       TxnCounter.SYSPROCS);
    }
    
    private final HStoreSite hstore_site;
    private final HStoreConf hstore_conf;
    private final int interval; // milliseconds
    private final Histogram<Integer> partition_txns = new Histogram<Integer>();
    private final TreeMap<Integer, ExecutionSite> executors;
    
    private Integer last_completed = null;
    private AtomicInteger snapshot_ctr = new AtomicInteger(0);
    
    private Integer inflight_min = null;
    private Integer inflight_max = null;
    
    private Integer processing_min = null;
    private Integer processing_max = null;
    
    private Thread self;

    /**
     * Maintain a set of tuples for the transaction profile times
     */
    private final Map<Procedure, LinkedBlockingDeque<long[]>> txn_profile_queues = new TreeMap<Procedure, LinkedBlockingDeque<long[]>>();
    private final Map<Procedure, long[]> txn_profile_totals = Collections.synchronizedSortedMap(new TreeMap<Procedure, long[]>());
    private TableUtil.Format txn_profile_format;
    private String txn_profiler_header[];
    
    final Map<String, Object> m_pool = new ListOrderedMap<String, Object>();
    final Map<String, Object> header = new ListOrderedMap<String, Object>();
    
    final TreeSet<Thread> sortedThreads = new TreeSet<Thread>(new Comparator<Thread>() {
        @Override
        public int compare(Thread o1, Thread o2) {
            return o1.getName().compareToIgnoreCase(o2.getName());
        }
    });
    
    public HStoreSiteStatus(HStoreSite hstore_site, HStoreConf hstore_conf) {
        this.hstore_site = hstore_site;
        this.hstore_conf = hstore_conf;
        
        // Pull the parameters we need from HStoreConf
        this.interval = hstore_conf.site.status_interval;
        
        this.executors = new TreeMap<Integer, ExecutionSite>();
        
        this.partition_txns.setKeepZeroEntries(true);
        for (Integer partition : hstore_site.getLocalPartitionIds()) {
            this.partition_txns.put(partition, 0);
            this.executors.put(partition, hstore_site.getExecutionSite(partition));
        } // FOR
        
        this.initTxnProfileInfo(hstore_site.catalog_db);
        
        this.header.put(String.format("%s Status", HStoreSite.class.getSimpleName()), hstore_site.getSiteName());
        this.header.put("Number of Partitions", this.executors.size());
    }
    
    @Override
    public void run() {
        self = Thread.currentThread();
        self.setName(this.hstore_site.getThreadName("mon"));
        if (hstore_conf.site.cpu_affinity)
            hstore_site.getThreadManager().registerProcessingThread();

        if (LOG.isDebugEnabled()) LOG.debug(String.format("Starting HStoreSite status monitor thread [interval=%d, kill=%s]", this.interval, hstore_conf.site.status_kill_if_hung));
        while (!self.isInterrupted() && this.hstore_site.isShuttingDown() == false) {
            try {
                Thread.sleep(this.interval);
            } catch (InterruptedException ex) {
                return;
            }
            if (this.hstore_site.isShuttingDown()) break;
            if (this.hstore_site.isReady() == false) continue;

            // Out we go!
            this.printSnapshot();
            
            // If we're not making progress, bring the whole thing down!
            int completed = TxnCounter.COMPLETED.get();
            if (hstore_conf.site.status_kill_if_hung && this.last_completed != null &&
                this.last_completed == completed && hstore_site.getInflightTxnCount() > 0) {
                String msg = String.format("HStoreSite #%d is hung! Killing the cluster!", hstore_site.getSiteId()); 
                LOG.fatal(msg);
                this.hstore_site.getMessenger().shutdownCluster(new RuntimeException(msg));
            }
            this.last_completed = completed;
        } // WHILE
    }
    
    private void printSnapshot() {
        LOG.info("STATUS SNAPSHOT #" + this.snapshot_ctr.incrementAndGet() + "\n" +
                 StringUtil.box(this.snapshot(hstore_conf.site.status_show_txn_info,
                                              hstore_conf.site.status_show_executor_info,
                                              hstore_conf.site.status_show_thread_info,
                                              hstore_conf.site.pool_profiling)));
    }
    
    @Override
    public void prepareShutdown() {
        // TODO Auto-generated method stub
        
    }
    
    @Override
    public void shutdown() {
        this.printSnapshot();
        if (hstore_conf.site.txn_profiling) {
            String csv = this.txnProfileCSV();
            if (csv != null) System.out.println(csv);
        }
        
//        for (ExecutionSite es : this.executors.values()) {
//            TransactionEstimator te = es.getTransactionEstimator();
//            ProfileMeasurement pm = te.CONSUME;
//            System.out.println(String.format("[%02d] CONSUME %.2fms total / %.2fms avg / %d calls",
//                                              es.getPartitionId(), pm.getTotalThinkTimeMS(), pm.getAverageThinkTimeMS(), pm.getInvocations()));
//            pm = te.CACHE;
//            System.out.println(String.format("     CACHE %.2fms total / %.2fms avg / %d calls",
//                                             pm.getTotalThinkTimeMS(), pm.getAverageThinkTimeMS(), pm.getInvocations()));
//            System.out.println(String.format("     ATTEMPTS %d / SUCCESS %d", te.batch_cache_attempts.get(), te.batch_cache_success.get())); 
//        }
        if (this.self != null) this.self.interrupt();
    }
    
    @Override
    public boolean isShuttingDown() {
        return this.hstore_site.isShuttingDown();
    }
    
    // ----------------------------------------------------------------------------
    // EXECUTION INFO
    // ----------------------------------------------------------------------------
    
    /**
     * 
     * @return
     */
    protected Map<String, Object> executorInfo() {
        int inflight_cur = hstore_site.getInflightTxnCount();
        if (inflight_min == null || inflight_cur < inflight_min) inflight_min = inflight_cur;
        if (inflight_max == null || inflight_cur > inflight_max) inflight_max = inflight_cur;
        
        Map<String, Object> m_exec = new ListOrderedMap<String, Object>();
        m_exec.put("Completed Txns", TxnCounter.COMPLETED.get());
        m_exec.put("InFlight Txns", String.format("%-5d [totalMin=%d, totalMax=%d, idle=%.2fms]",
                        inflight_cur,
                        inflight_min,
                        inflight_max,
                        this.hstore_site.idle_time.getTotalThinkTimeMS()
                        
        ));
        if (hstore_conf.site.exec_postprocessing_thread) {
            int processing_cur = hstore_site.getQueuedResponseCount();
            if (processing_min == null || processing_cur < processing_min) processing_min = processing_cur;
            if (processing_max == null || processing_cur > processing_max) processing_max = processing_cur;
            m_exec.put("Post-Processing Txns", String.format("%-5d [totalMin=%d, totalMax=%d]",
                            processing_cur,
                            processing_min,
                            processing_max
            ));
        }

//        m_exec.put("Redirect Throttle", String.format("%-5s [limit=%d, release=%d, time=%.2fms]\n",
//                        this.hstore_site.isRedirectedThrottled(),
//                        this.hstore_site.getRedirectQueueMax(),
//                        this.hstore_site.getRedirectQueueRelease(),
//                        this.hstore_site.redirect_throttle_time.getTotalThinkTimeMS()                              
//        ));

        
        for (Entry<Integer, ExecutionSite> e : this.executors.entrySet()) {
            ExecutionSite es = e.getValue();
            int partition = e.getKey().intValue();
            TransactionState ts = es.getCurrentDtxn();
            
            // Queue Information
            Map<String, Object> m = new ListOrderedMap<String, Object>();
            
            m.put(String.format("%3d total / %3d queued / %3d blocked / %3d waiting\n",
                                    this.partition_txns.get(partition),
                                    es.getWorkQueueSize(),
                                    es.getBlockedQueueSize(),
                                    es.getWaitingQueueSize()), null);
            
            // Execution Info
            m.put("Incoming Throttle", String.format("%-5s [limit=%d, release=%d, time=%.2fms]",
                    this.hstore_site.isIncomingThrottled(partition),
                    this.hstore_site.getIncomingQueueMax(),
                    this.hstore_site.getIncomingQueueRelease(),
                    this.hstore_site.incoming_throttle_time[partition].getTotalThinkTimeMS()
            ));
            
            m.put("Current DTXN", (ts == null ? "-" : ts));
            m.put("Current Mode", es.getExecutionMode());
            
            // Queue Time
            if (hstore_conf.site.exec_profiling) {
                ProfileMeasurement pm = es.getWorkExecTime();
                m.put("Txn Execution", String.format("%d total / %.2fms total / %.2fms avg",
                                                pm.getInvocations(),
                                                pm.getTotalThinkTimeMS(),
                                                pm.getAverageThinkTimeMS()));
                
                pm = es.getWorkIdleTime();
                m.put("Idle Time", String.format("%.2fms total / %.2fms avg",
                                                pm.getTotalThinkTimeMS(),
                                                pm.getAverageThinkTimeMS()));
            }
            
            m_exec.put(String.format("    Partition[%02d]", partition), StringUtil.formatMaps(m) + "\n");
        } // FOR
        
        // Incoming Partition Distribution
        m_exec.put("Incoming Txns\nBase Partitions", hstore_site.getIncomingPartitionHistogram().toString(50, 4));
        
        return (m_exec);
    }
    
    // ----------------------------------------------------------------------------
    // TRANSACTION EXECUTION INFO
    // ----------------------------------------------------------------------------
    
    /**
     * 
     * @return
     */
    protected Map<String, String> txnExecInfo() {
        Set<TxnCounter> cnts_to_include = new TreeSet<TxnCounter>();
        Set<String> procs = TxnCounter.getAllProcedures();
        if (procs.isEmpty()) return (null);
        for (TxnCounter tc : TxnCounter.values()) {
            if (TXNINFO_ALWAYS_SHOW.contains(tc) || (tc.get() > 0 && TXNINFO_EXCLUDES.contains(tc) == false)) cnts_to_include.add(tc);
        } // FOR
        
        boolean first = true;
        int num_cols = cnts_to_include.size() + 1;
        String header[] = new String[num_cols];
        Object rows[][] = new String[procs.size()+2][];
        String col_delimiters[] = new String[num_cols];
        String row_delimiters[] = new String[rows.length];
        int i = -1;
        int j = 0;
        for (String proc_name : procs) {
            j = 0;
            rows[++i] = new String[num_cols];
            rows[i][j++] = proc_name;
            if (first) header[0] = "";
            for (TxnCounter tc : cnts_to_include) {
                if (first) header[j] = tc.toString().replace("partition", "P");
                Long cnt = tc.getHistogram().get(proc_name);
                rows[i][j++] = (cnt != null ? Long.toString(cnt) : "-");
            } // FOR
            first = false;
        } // FOR
        
        j = 0;
        rows[++i] = new String[num_cols];
        rows[i+1] = new String[num_cols];
        rows[i][j++] = "TOTAL";
        row_delimiters[i] = "-"; // "\u2015";
        
        for (TxnCounter tc : cnts_to_include) {
            if (TXNINFO_COL_DELIMITERS.contains(tc)) col_delimiters[j] = " | ";
            
            if (tc == TxnCounter.COMPLETED || tc == TxnCounter.RECEIVED) {
                rows[i][j] = Integer.toString(tc.get());
                rows[i+1][j] = "";
            } else {
                Double ratio = tc.ratio();
                rows[i][j] = (ratio == null ? "-" : Integer.toString(tc.get()));
                rows[i+1][j] = (ratio == null ? "-": String.format("%.3f", ratio));
            }
            j++;
        } // FOR
        
        if (debug.get()) {
            for (i = 0; i < rows.length; i++) {
                LOG.debug("ROW[" + i + "]: " + Arrays.toString(rows[i]));
            }
        }
        TableUtil.Format f = new TableUtil.Format("   ", col_delimiters, row_delimiters, true, false, true, false, false, false, true, true, null);
        return (TableUtil.tableMap(f, header, rows));
    }
    
    // ----------------------------------------------------------------------------
    // THREAD INFO
    // ----------------------------------------------------------------------------
    
    /**
     * 
     * @return
     */
    protected Map<String, Object> threadInfo() {
        HStoreThreadManager manager = hstore_site.getThreadManager();
        assert(manager != null);
        
        final Map<String, Object> m_thread = new ListOrderedMap<String, Object>();
        final Map<Thread, StackTraceElement[]> threads = Thread.getAllStackTraces();
        sortedThreads.clear();
        sortedThreads.addAll(threads.keySet());
        
        m_thread.put("Number of Threads", threads.size());
        
        for (Thread t : sortedThreads) {
            StackTraceElement stack[] = threads.get(t);
            
            String name = StringUtil.abbrv(t.getName(), 24, true);
            if (manager.isRegistered(t) == false) {
                name += " *UNREGISTERED*";
            }
            
            String trace = null;
            if (stack.length == 0) {
                trace += "<NO STACK TRACE>";
//            } else if (t.getName().startsWith("Thread-")) {
//                trace = Arrays.toString(stack);
            } else {
                // Find the first line that is interesting to us
                for (int i = 0; i < stack.length; i++) {
                    if (THREAD_REGEX.matcher(stack[i].getClassName()).matches()) {
                        trace += stack[i].toString();
                        break;
                    }
                } // FOR
                if (trace == null) stack[0].toString();
            }
            m_thread.put(name, trace);
        } // FOR
        return (m_thread);
    }
    
    // ----------------------------------------------------------------------------
    // TRANSACTION PROFILING
    // ----------------------------------------------------------------------------
    
    /**
     * 
     * @param catalog_db
     */
    private void initTxnProfileInfo(Database catalog_db) {
        // COLUMN DELIMITERS
        String last_prefix = null;
        String col_delimiters[] = new String[TransactionProfile.PROFILE_FIELDS.length + 2];
        int col_idx = 0;
        for (Field f : TransactionProfile.PROFILE_FIELDS) {
            String prefix = f.getName().split("_")[1];
            assert(prefix.isEmpty() == false);
            if (last_prefix != null && col_idx > 0 && prefix.equals(last_prefix) == false) {
                col_delimiters[col_idx+1] = " | ";        
            }
            col_idx++;
            last_prefix = prefix;
        } // FOR
        this.txn_profile_format = new TableUtil.Format("   ", col_delimiters, null, true, false, true, false, false, false, true, true, "-");
        
        // TABLE HEADER
        int idx = 0;
        this.txn_profiler_header = new String[TransactionProfile.PROFILE_FIELDS.length + 2];
        this.txn_profiler_header[idx++] = "";
        this.txn_profiler_header[idx++] = "txns";
        for (int i = 0; i < TransactionProfile.PROFILE_FIELDS.length; i++) {
            String name = TransactionProfile.PROFILE_FIELDS[i].getName()
                                .replace("pm_", "")
                                .replace("_total", "");
            this.txn_profiler_header[idx++] = name;
        } // FOR
        
        // PROCEDURE TOTALS
        for (Procedure catalog_proc : catalog_db.getProcedures()) {
            if (catalog_proc.getSystemproc()) continue;
            this.txn_profile_queues.put(catalog_proc, new LinkedBlockingDeque<long[]>());
            
            long totals[] = new long[TransactionProfile.PROFILE_FIELDS.length + 1];
            for (int i = 0; i < totals.length; i++) {
                totals[i] = 0;
            } // FOR
            this.txn_profile_totals.put(catalog_proc, totals);
        } // FOR
    }
    
    /**
     * 
     * @param tp
     */
    public void addTxnProfile(Procedure catalog_proc, TransactionProfile tp) {
        assert(catalog_proc != null);
        assert(tp.isStopped());
        if (trace.get()) LOG.info("Calculating TransactionProfile information");

        long tuple[] = tp.getTuple();
        assert(tuple != null);
        if (trace.get()) LOG.trace(String.format("Appending TransactionProfile: %s", tp, Arrays.toString(tuple)));
        this.txn_profile_queues.get(catalog_proc).offer(tuple);
    }
    
    private void calculateTxnProfileTotals(Procedure catalog_proc) {
        long totals[] = this.txn_profile_totals.get(catalog_proc);
        
        long tuple[] = null;
        LinkedBlockingDeque<long[]> queue = this.txn_profile_queues.get(catalog_proc); 
        while ((tuple = queue.poll()) != null) {
            totals[0]++;
            for (int i = 0, cnt = tuple.length; i < cnt; i++) {
                totals[i+1] += tuple[i];
            } // FOR
        } // FOR
    }
    
    /**
     * 
     * TODO: This should be broken out in a separate component that stores the data
     *       down in the EE. That way we can extract it in a variety of ways
     * 
     * @return
     */
    private Object[][] generateTxnProfileSnapshot() {
        // TABLE ROWS
        List<Object[]> rows = new ArrayList<Object[]>(); 
        for (Entry<Procedure, long[]> e : this.txn_profile_totals.entrySet()) {
            this.calculateTxnProfileTotals(e.getKey());
            long totals[] = e.getValue();
            if (totals[0] == 0) continue;

            int col_idx = 0;
            Object row[] = new String[this.txn_profiler_header.length];
            row[col_idx++] = e.getKey().getName();
            
            for (int i = 0; i < totals.length; i++) {
                // # of Txns
                if (i == 0) {
                    row[col_idx++] = Long.toString(totals[i]);
                // Everything Else
                } else {
                    row[col_idx++] = (totals[i] > 0 ? String.format("%.02f", totals[i] / 1000000d) : null);
                }
            } // FOR
            if (debug.get()) LOG.debug("ROW[" + rows.size() + "] " + Arrays.toString(row));
            rows.add(row);
        } // FOR
        if (rows.isEmpty()) return (null);
        Object rows_arr[][] = rows.toArray(new String[rows.size()][this.txn_profiler_header.length]);
        assert(rows_arr.length == rows.size());
        return (rows_arr);
    }
    
    public Map<String, String> txnProfileInfo() {
        Object rows[][] = this.generateTxnProfileSnapshot();
        if (rows == null) return (null);
        return (TableUtil.tableMap(this.txn_profile_format, this.txn_profiler_header, rows));
    }
    
    public String txnProfileCSV() {
        Object rows[][] = this.generateTxnProfileSnapshot();
        if (rows == null) return (null);
        
        if (debug.get()) {
            for (int i = 0; i < rows.length; i++) {
                if (i == 0) LOG.debug("HEADER: " + Arrays.toString(this.txn_profiler_header));
                LOG.debug("ROW[" + i + "] " + Arrays.toString(rows[i]));
            } // FOR
        }
        TableUtil.Format f = TableUtil.defaultCSVFormat().clone();
        f.replace_null_cells = 0;
        f.prune_null_rows = true;
        return (TableUtil.table(f, this.txn_profiler_header, rows));
    }
    
    // ----------------------------------------------------------------------------
    // SNAPSHOT PRETTY PRINTER
    // ----------------------------------------------------------------------------
    
    public synchronized String snapshot(boolean show_txns, boolean show_exec, boolean show_threads, boolean show_poolinfo) {
        this.partition_txns.clearValues();
        for (Entry<Long, LocalTransactionState> e : hstore_site.getAllTransactions()) {
            this.partition_txns.put(e.getValue().getBasePartition());
        } // FOR
        
        // ----------------------------------------------------------------------------
        // Transaction Information
        // ----------------------------------------------------------------------------
        Map<String, String> m_txn = (show_txns ? this.txnExecInfo() : null);
        
        // ----------------------------------------------------------------------------
        // Executor Information
        // ----------------------------------------------------------------------------
        Map<String, Object> m_exec = (show_exec ? this.executorInfo() : null);

        // ----------------------------------------------------------------------------
        // Thread Information
        // ----------------------------------------------------------------------------
        Map<String, Object> threadInfo = null;
        Map<String, Object> cpuThreads = null;
        if (show_threads) {
            threadInfo = this.threadInfo();
            
//            cpuThreads = new ListOrderedMap<String, Object>();
//            for (Entry<Integer, Set<Thread>> e : hstore_site.getThreadManager().getCPUThreads().entrySet()) {
//                TreeSet<String> names = new TreeSet<String>();
//                for (Thread t : e.getValue())
//                    names.add(t.getName());
//                cpuThreads.put("CPU #" + e.getKey(), StringUtil.columns(names.toArray(new String[0])));
//            } // FOR
        }

        // ----------------------------------------------------------------------------
        // Transaction Profiling
        // ----------------------------------------------------------------------------
        Map<String, String> txnProfiles = (hstore_conf.site.txn_profiling ? this.txnProfileInfo() : null);
        
        // ----------------------------------------------------------------------------
        // Object Pool Information
        // ----------------------------------------------------------------------------
        m_pool.clear();
        if (show_poolinfo) {
            // BatchPlanners
            m_pool.put("BatchPlanners", ExecutionSite.POOL_BATCH_PLANNERS.size());

            // MarkovPathEstimators
            StackObjectPool pool = (StackObjectPool)TransactionEstimator.getEstimatorPool();
            CountingPoolableObjectFactory<?> factory = (CountingPoolableObjectFactory<?>)pool.getFactory();
            m_pool.put("Estimators", this.formatPoolCounts(pool, factory));

            // TransactionEstimator.States
            pool = (StackObjectPool)TransactionEstimator.getStatePool();
            factory = (CountingPoolableObjectFactory<?>)pool.getFactory();
            m_pool.put("EstimationStates", this.formatPoolCounts(pool, factory));
            
            // DependencyInfos
            pool = (StackObjectPool)DependencyInfo.INFO_POOL;
            factory = (CountingPoolableObjectFactory<?>)pool.getFactory();
            m_pool.put("DependencyInfos", this.formatPoolCounts(pool, factory));
            
            // ForwardTxnRequestCallbacks
            pool = (StackObjectPool)HStoreSite.POOL_FORWARDTXN_REQUEST;
            factory = (CountingPoolableObjectFactory<?>)pool.getFactory();
            m_pool.put("ForwardTxnRequests", this.formatPoolCounts(pool, factory));
            
            // ForwardTxnResponseCallbacks
            pool = (StackObjectPool)HStoreSite.POOL_FORWARDTXN_RESPONSE;
            factory = (CountingPoolableObjectFactory<?>)pool.getFactory();
            m_pool.put("ForwardTxnResponses", this.formatPoolCounts(pool, factory));
            
            // BatchPlans
            int active = 0;
            int idle = 0;
            int created = 0;
            int passivated = 0;
            int destroyed = 0;
            for (BatchPlanner bp : ExecutionSite.POOL_BATCH_PLANNERS.values()) {
                pool = (StackObjectPool)bp.getBatchPlanPool();
                factory = (CountingPoolableObjectFactory<?>)pool.getFactory();
                
                active += pool.getNumActive();
                idle += pool.getNumIdle();
                created += factory.getCreatedCount();
                passivated += factory.getPassivatedCount();
                destroyed += factory.getDestroyedCount();
            } // FOR
            m_pool.put("BatchPlans", String.format(POOL_FORMAT, active, idle, created, destroyed, passivated));
            
            // Partition Specific
            String labels[] = new String[] {
                "LocalTxnState",
                "RemoteTxnState",
            };
            int total_active[] = new int[labels.length];
            int total_idle[] = new int[labels.length];
            int total_created[] = new int[labels.length];
            int total_passivated[] = new int[labels.length];
            int total_destroyed[] = new int[labels.length];
            for (int i = 0, cnt = labels.length; i < cnt; i++) {
                total_active[i] = total_idle[i] = total_created[i] = total_passivated[i] = total_destroyed[i] = 0;
            }
            
            for (ExecutionSite e : executors.values()) {
                int i = 0;
                for (ObjectPool p : new ObjectPool[] { e.localTxnPool, e.remoteTxnPool }) {
                    pool = (StackObjectPool)p;
                    factory = (CountingPoolableObjectFactory<?>)pool.getFactory();
                    
                    total_active[i] += p.getNumActive();
                    total_idle[i] += p.getNumIdle(); 
                    total_created[i] += factory.getCreatedCount();
                    total_passivated[i] += factory.getPassivatedCount();
                    total_destroyed[i] += factory.getDestroyedCount();
                    i += 1;
                } // FOR
            } // FOR
            
            for (int i = 0, cnt = labels.length; i < cnt; i++) {
                m_pool.put(labels[i], String.format(POOL_FORMAT, total_active[i], total_idle[i], total_created[i], total_destroyed[i], total_passivated[i]));
            } // FOR
        }
        return (StringUtil.formatMaps(header, m_exec, m_txn, threadInfo, cpuThreads, txnProfiles, m_pool));
    }
    
    private String formatPoolCounts(StackObjectPool pool, CountingPoolableObjectFactory<?> factory) {
        return (String.format(POOL_FORMAT, pool.getNumActive(),
                                           pool.getNumIdle(),
                                           factory.getCreatedCount(),
                                           factory.getDestroyedCount(),
                                           factory.getPassivatedCount()));
    }
} // END CLASS