package edu.brown.markov;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.Map.Entry;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONStringer;
import org.voltdb.catalog.Database;
import org.voltdb.catalog.Procedure;

import edu.brown.catalog.CatalogKey;
import edu.brown.utils.CollectionUtil;
import edu.brown.utils.JSONSerializable;
import edu.brown.utils.JSONUtil;

/**
 * Convenience wrapper for a collection of Procedure-based MarkovGraphs that are split on some unique id 
 * <Id> -> <Procedure> -> <MarkovGraph> 
 * @author pavlo
 */
public class MarkovGraphsContainer implements JSONSerializable {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(MarkovGraphsContainer.class);

    public enum Members {
        MARKOVS,
        FEATURES;
    }
    
    private final SortedMap<Integer, Map<Procedure,MarkovGraph>> markovs = new TreeMap<Integer, Map<Procedure,MarkovGraph>>();
    
    /**
     * 
     */
    private final Map<Procedure, List<String>> features = new HashMap<Procedure, List<String>>();
    
    
    public void clear() {
        this.markovs.clear();
        this.features.clear();
    }
    
    public MarkovGraph get(Integer id, Procedure catalog_proc) {
        Map<Procedure, MarkovGraph> inner = this.markovs.get(id);
        return (inner != null ? inner.get(catalog_proc) : null);
    }
    
    public MarkovGraph getOrCreate(Integer id, Procedure catalog_proc) {
        MarkovGraph markov = this.get(id, catalog_proc);
        if (markov == null) {
            markov = new MarkovGraph(catalog_proc);
            this.put(id, markov);
        }
        return (markov);
    }
    
    public void put(Integer id, MarkovGraph markov) {
        Map<Procedure, MarkovGraph> inner = this.markovs.get(id);
        if (inner == null) {
            inner = new HashMap<Procedure, MarkovGraph>();
            this.markovs.put(id, inner);
        }
        inner.put(markov.getProcedure(), markov);
    }
    
    /**
     * 
     * @param txn_id
     * @param base_partition TODO
     * @param params
     * @param catalog_proc
     * @return
     */
    public MarkovGraph getFromParams(long txn_id, int base_partition, Object params[], Procedure catalog_proc) {
        MarkovGraph m = null;
        List<String> proc_features = this.features.get(catalog_proc);
        if (proc_features == null) {
            m = this.get(base_partition, catalog_proc);
        } else {
            assert(false) : "To be implemented!";
        }
        return (m);
    }
    
    
    public void setFeatureKeys(Procedure catalog_proc, List<String> keys) {
        List<String> inner = this.features.get(catalog_proc);
        if (inner == null) {
            inner = new ArrayList<String>();
            this.features.put(catalog_proc, inner);
        }
        inner.addAll(keys);
    }
    
    public List<String> getFeatureKeys(Procedure catalog_proc) {
        return (this.features.get(catalog_proc));
    }
    
    protected void copy(MarkovGraphsContainer other) {
        this.features.clear();
        this.features.putAll(other.features);
        this.markovs.clear();
        this.markovs.putAll(other.markovs);
    }
    
    protected Set<Integer> keySet() {
        return this.markovs.keySet();
    }
    
    protected Set<Entry<Integer, Map<Procedure, MarkovGraph>>> entrySet() {
        return this.markovs.entrySet();
    }
    
    public int size() {
        return (this.markovs.size());
    }
    
    // -----------------------------------------------------------------
    // SERIALIZATION
    // -----------------------------------------------------------------
    
    @Override
    public void load(String input_path, Database catalog_db) throws IOException {
        JSONUtil.load(this, catalog_db, input_path);
    }

    @Override
    public void save(String output_path) throws IOException {
        JSONUtil.save(this, output_path);
    }
    
    @Override
    public String toJSONString() {
        return (JSONUtil.toJSONString(this));
    }

    @Override
    public void toJSON(JSONStringer stringer) throws JSONException {
        // FEATURE KEYS
        stringer.key(Members.FEATURES.name()).object();
        for (Procedure catalog_proc : this.features.keySet()) {
            stringer.key(CatalogKey.createKey(catalog_proc)).array();
            for (String feature_key : this.features.get(catalog_proc)) {
                stringer.value(feature_key);
            } // FOR (feature)
            stringer.endArray();
        } // FOR (procedure)
        stringer.endObject();

        // MARKOV GRAPHS
        stringer.key(Members.MARKOVS.name()).object();
        for (Integer id : this.markovs.keySet()) {
            // Roll through each id and create a new JSONObject per id
            LOG.debug("Serializing " + this.markovs.get(id).size() + " graphs for id " + id);
            stringer.key(id.toString()).object();
            for (Entry<Procedure, MarkovGraph> e : this.markovs.get(id).entrySet()) {
                stringer.key(CatalogKey.createKey(e.getKey())).object();
                e.getValue().toJSON(stringer);
                stringer.endObject();
            } // FOR
            stringer.endObject();
        } // FOR
        stringer.endObject();
    }

    @Override
    public void fromJSON(JSONObject json_object, Database catalog_db) throws JSONException {
        // FEATURE KEYS
        JSONObject json_inner = json_object.getJSONObject(Members.FEATURES.name());
        for (String proc_key : CollectionUtil.wrapIterator(json_inner.keys())) {
            Procedure catalog_proc = CatalogKey.getFromKey(catalog_db, proc_key, Procedure.class);
            assert(catalog_proc != null);
            
            JSONArray json_arr = json_inner.getJSONArray(proc_key);
            List<String> feature_keys = new ArrayList<String>(); 
            for (int i = 0, cnt = json_arr.length(); i < cnt; i++) {
                feature_keys.add(json_arr.getString(i));
            } // FOR
            this.features.put(catalog_proc, feature_keys);
        } // FOR (proc key)
        
        // MARKOV GRAPHS
        json_inner = json_object.getJSONObject(Members.MARKOVS.name());
        for (String id_key : CollectionUtil.wrapIterator(json_inner.keys())) {
            Integer id = Integer.valueOf(id_key);
        
            JSONObject json_procs = json_inner.getJSONObject(id_key);
            assert(json_procs != null);
            
            for (String proc_key : CollectionUtil.wrapIterator(json_procs.keys())) {
                Procedure catalog_proc = CatalogKey.getFromKey(catalog_db, proc_key, Procedure.class);
                assert(catalog_proc != null);
                
                JSONObject json_graph = json_procs.getJSONObject(proc_key);
                MarkovGraph markov = new MarkovGraph(catalog_proc);
                markov.fromJSON(json_graph, catalog_db);
                this.put(id, markov);
            } // FOR (proc key)
        } // FOR (id key)
    }
}
