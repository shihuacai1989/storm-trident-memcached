package trident.memcached;

import backtype.storm.task.IMetricsContext;
import backtype.storm.topology.ReportedFailedException;
import backtype.storm.tuple.Values;
import backtype.storm.Config;
import backtype.storm.metric.api.CountMetric;

import java.io.IOException;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;


import clojure.lang.Obj;
import net.spy.memcached.MemcachedClient;
import storm.trident.state.JSONNonTransactionalSerializer;
import storm.trident.state.JSONOpaqueSerializer;
import storm.trident.state.JSONTransactionalSerializer;
import storm.trident.state.OpaqueValue;
import storm.trident.state.Serializer;
import storm.trident.state.State;
import storm.trident.state.map.IBackingMap;
import storm.trident.state.StateFactory;
import storm.trident.state.StateType;
import storm.trident.state.TransactionalValue;
import storm.trident.state.map.CachedMap;
import storm.trident.state.map.MapState;
import storm.trident.state.map.NonTransactionalMap;
import storm.trident.state.map.OpaqueMap;
import storm.trident.state.map.SnapshottableMap;
import storm.trident.state.map.TransactionalMap;

public class MemcachedState<T> implements IBackingMap<T> {
    private static final Map<StateType, Serializer> DEFAULT_SERIALZERS = new HashMap<StateType, Serializer>() {{
        put(StateType.NON_TRANSACTIONAL, new JSONNonTransactionalSerializer());
        put(StateType.TRANSACTIONAL, new JSONTransactionalSerializer());
        put(StateType.OPAQUE, new JSONOpaqueSerializer());
    }};

    public static class Options<T> implements Serializable {
        public int localCacheSize = 1000;
        public String globalKey = "$GLOBAL$";
        public Serializer<T> serializer = null;
        public long expiration = 0;
        public int requestRetries = 2;         // max number of retries after the first failure.
        public int connectTimeoutMillis = 200; // tcp connection timeout.
        public int requestTimeoutMillis = 50;  // request timeout.
        public int e2eTimeoutMillis = 500;     // end-to-end request timeout.
        public int hostConnectionLimit = 10;   // concurrent connections to one server.
        public int maxWaiters = 2;             // max waiters in the request queue.
        public int maxMultiGetBatchSize = 100;
    }  

    public static StateFactory opaque(List<InetSocketAddress> servers) {
        return opaque(servers, new Options());
    }

    public static StateFactory opaque(List<InetSocketAddress> servers, Options<OpaqueValue> opts) {
        return new Factory(servers, StateType.OPAQUE, opts);
    }

    public static StateFactory transactional(List<InetSocketAddress> servers) {
        return transactional(servers, new Options());
    }

    public static StateFactory transactional(List<InetSocketAddress> servers, Options<TransactionalValue> opts) {
        return new Factory(servers, StateType.TRANSACTIONAL, opts);
    }

    public static StateFactory nonTransactional(List<InetSocketAddress> servers) {
        return nonTransactional(servers, new Options());
    }

    public static StateFactory nonTransactional(List<InetSocketAddress> servers, Options<Object> opts) {
        return new Factory(servers, StateType.NON_TRANSACTIONAL, opts);
    }

    protected static class Factory implements StateFactory {
        StateType _type;
        List<InetSocketAddress> _servers;
        Serializer _ser;
        Options _opts;

        public Factory(List<InetSocketAddress> servers, StateType type, Options options) {
            _type = type;
            _servers = servers;
            _opts = options;
            if(options.serializer==null) {
                _ser = DEFAULT_SERIALZERS.get(type);
                if(_ser==null) {
                    throw new RuntimeException("Couldn't find serializer for state type: " + type);
                }
            } else {
                _ser = options.serializer;
            }
        }

        @Override
        public State makeState(Map conf, IMetricsContext context, int partitionIndex, int numPartitions) {
            //State
            MemcachedState s;
            try {
                s = new MemcachedState(makeMemcachedClient(_opts, _servers), _opts, _ser);
            } catch (UnknownHostException e) {
                throw new RuntimeException(e);
            }
            s.registerMetrics(conf, context);
            CachedMap c = new CachedMap(s, _opts.localCacheSize);
            MapState ms;
            if(_type == StateType.NON_TRANSACTIONAL) {
                ms = NonTransactionalMap.build(c);
            } else if(_type==StateType.OPAQUE) {
                ms = OpaqueMap.build(c);
            } else if(_type==StateType.TRANSACTIONAL){
                ms = TransactionalMap.build(c);
            } else {
                throw new RuntimeException("Unknown state type: " + _type);
            }
            return new SnapshottableMap(ms, new Values(_opts.globalKey));
        }

      /**
       * Constructs a finagle java memcached client for the list of endpoints..
       *
       * @param endpoints list of {@code InetSocketAddress} for all the memcached servers.
       * @return  to read/write to the hash ring of the servers..
       */
      static MemcachedClient makeMemcachedClient(Options opts, List<InetSocketAddress> endpoints)
          throws UnknownHostException {
          try {
              MemcachedClient mc = new MemcachedClient(endpoints);
              return mc;
          } catch (IOException e) {
              e.printStackTrace();
          }
          return null;
      }

      /**
       * Constructs a host:port:weight tuples string of all the passed endpoints.
       *
       * @param endpoints list of {@code InetSocketAddress} for all the memcached servers.
       * @return Comma-separated string of host:port:weight tuples.
       */
      static String getHostPortWeightTuples(List<InetSocketAddress> endpoints) throws UnknownHostException {
          final int defaultWeight = 1;
          final StringBuilder tuples = new StringBuilder(1024);
          for (InetSocketAddress endpoint : endpoints) {
              if (tuples.length() > 0) {
                  tuples.append(",");
              }
              tuples.append(String.format("%s:%d:%d", endpoint.getHostName(), endpoint.getPort(), defaultWeight));
          }
          return tuples.toString();
      }
    }
    
    private final MemcachedClient _client;
    private Options _opts;
    private Serializer _ser;
    CountMetric _mreads;
    CountMetric _mwrites;
    CountMetric _mexceptions;
    
    public MemcachedState(MemcachedClient client, Options opts, Serializer<T> ser) {
        _client = client;
        _opts = opts;
        _ser = ser;

    }

    @Override
    public List<T> multiGet(List<List<Object>> keys) {
        try {
            LinkedList<String> singleKeys = new LinkedList();
            for(List<Object> key: keys) {
                singleKeys.add(toSingleKey(key));
            }
            List<T> ret = new ArrayList(singleKeys.size());
            while(!singleKeys.isEmpty()) {
                List<String> getBatch = new ArrayList<String>(_opts.maxMultiGetBatchSize);
                for(int i=0; i<_opts.maxMultiGetBatchSize && !singleKeys.isEmpty(); i++) {
                    getBatch.add(singleKeys.removeFirst());
                }
                Map<String, Object> result = _client.getBulk(getBatch);
                for(String k: getBatch) {
                    Object entry = result.get(k);
                    if (entry != null) {
                      T val = (T)_ser.deserialize((byte[])entry);
                      ret.add(val);
                    } else {
                      ret.add(null);
                    }
                }
            }
	    _mreads.incrBy(ret.size());
            return ret;
        } catch(Exception e) {
            checkMemcachedException(e);
            //e.printStackTrace();
            throw new IllegalStateException("Impossible to reach this code" + e);
        }
        //return null;
    }

    @Override
    public void multiPut(List<List<Object>> keys, List<T> vals) {
        try {
            //List<Future> futures = new ArrayList(keys.size());
            for(int i=0; i<keys.size(); i++) {
                String key = toSingleKey(keys.get(i));
                T val = vals.get(i);
                byte[] serialized = _ser.serialize(val);
                _client.set(key, 60 * 60 * 24 * 30, serialized);
            }

            /*for(Future future: futures) {
                future.get();
            }*/
	    _mwrites.incrBy(vals.size());
        } catch(Exception e) {

            checkMemcachedException(e);

        }
    }
    
    
    private void checkMemcachedException(Exception e) {
	_mexceptions.incr();
       /* if(e instanceof RequestException ||
           e instanceof ChannelException ||
           e instanceof ServiceException ||
           e instanceof ApplicationException ||
           e instanceof ApiException ||
           e instanceof CodecException ||
           e instanceof ChannelBufferUsageException) {
            throw new ReportedFailedException(e);
        } else {
            throw new RuntimeException(e);
        }     */
    }

    private void registerMetrics(Map conf, IMetricsContext context) {
        Object o = conf.get(Config.TOPOLOGY_BUILTIN_METRICS_BUCKET_SIZE_SECS);
        int bucketSize = Integer.valueOf(String.valueOf(o));
      //int bucketSize = (int)(conf.get(Config.TOPOLOGY_BUILTIN_METRICS_BUCKET_SIZE_SECS));
      _mreads = context.registerMetric("memcached/readCount", new CountMetric(), bucketSize);
      _mwrites = context.registerMetric("memcached/writeCount", new CountMetric(), bucketSize);
      _mexceptions = context.registerMetric("memcached/exceptionCount", new CountMetric(), bucketSize);
    }

    private String toSingleKey(List<Object> key) {
        if(key.size()!=1) {
            throw new RuntimeException("Memcached state does not support compound keys");
        }
        return key.get(0).toString();
    }
}
