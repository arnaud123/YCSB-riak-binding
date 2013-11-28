package riakBinding.java;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import com.basho.riak.client.IRiakClient;
import com.basho.riak.client.RiakException;
import com.basho.riak.client.RiakFactory;
import com.basho.riak.client.RiakRetryFailedException;
import com.basho.riak.client.bucket.Bucket;
import com.basho.riak.client.cap.Quora;
import com.basho.riak.client.operations.DeleteObject;
import com.basho.riak.client.operations.FetchObject;
import com.basho.riak.client.operations.StoreObject;
import com.basho.riak.client.raw.http.HTTPClientConfig;
import com.basho.riak.client.raw.http.HTTPClusterConfig;
import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.DB;
import com.yahoo.ycsb.DBException;

/*
Copyright 2013 KU Leuven Research and Development - iMinds - Distrinet

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Administrative Contact: dnet-project-office@cs.kuleuven.be
Technical Contact: arnaud.schoonjans@student.kuleuven.be
*/
public class RiakClient extends DB {

	public static final int OK = 0;
	public static final int ERROR = -1;
	private static final Quora DEFAULT_READ_QUORUM = Quora.ONE;
	private static final Quora DEFAULT_WRITE_QUORUM = Quora.ONE;
	private static final Quora DEFAULT_DELETE_QUORA = Quora.ONE;
	
	private final int maxConnections = 50;
	private IRiakClient client;

	// Constructor for testing purposes
	public RiakClient(IRiakClient client){
		if(client == null)
			throw new IllegalArgumentException("cient is null");
		this.client = client;
	}
	
	public RiakClient(){
		this.client = null;
	}
	
	private String[] getIpAddressesOfNodes() throws DBException {
		String hosts = getProperties().getProperty("hosts");
		if (hosts == null)
			throw new DBException("Required property \"hosts\" missing for RiakClient");
		return hosts.split(",");
	}

	private HTTPClusterConfig getClusterConfiguration() throws DBException {
		String[] hosts = this.getIpAddressesOfNodes();
		HTTPClusterConfig clusterConfig = new HTTPClusterConfig(
				this.maxConnections);
		HTTPClientConfig httpClientConfig = HTTPClientConfig.defaults();
		clusterConfig.addHosts(httpClientConfig, hosts);
		return clusterConfig;
	}

	@Override
	public void init() throws DBException {
		HTTPClusterConfig clusterConfig = getClusterConfiguration();
		try {
			this.client = RiakFactory.newClient(clusterConfig);
		} catch (RiakException e) {
			throw new DBException("Unable to connect to cluster nodes");
		}
	}

	@Override
	public void cleanup() throws DBException {
		if (this.client != null) {
			this.client.shutdown();
		}
	}

	private StringToStringMap executeReadQuery(String bucketName, String key) {
		try {
			Bucket bucket = this.client.fetchBucket(bucketName).execute();
			FetchObject<StringToStringMap> fetchObj = bucket.fetch(key, StringToStringMap.class);
			return fetchObj.r(DEFAULT_READ_QUORUM).execute();
		} catch (RiakRetryFailedException exc) {
			return null;
		}
	}
	
	private int executeWriteQuery(String bucketName, String key, StringToStringMap dataToWrite){
		try {
			Bucket bucket = this.client.fetchBucket(bucketName).execute();
			StoreObject<StringToStringMap> storeObject = bucket.store(key, dataToWrite);
			storeObject.w(DEFAULT_WRITE_QUORUM).execute();
		} catch (RiakRetryFailedException e) {
			return ERROR;
		}
		return OK;
	}
	
	private void copyRequestedFieldsToResultMap(Set<String> fields,
			StringToStringMap inputMap,
			HashMap<String, ByteIterator> result) {
		for (String field : fields) {
			ByteIterator value = inputMap.getAsByteIt(field);
			result.put(field, value);
		}
	}

	private void copyAllFieldsToResultMap(StringToStringMap inputMap, 
								Map<String, ByteIterator> result){
		for(String key: inputMap.keySet()){
			ByteIterator value = inputMap.getAsByteIt(key);
			result.put(key, value);
		}
	}
	
	@Override
	public int read(String bucketName, String key, Set<String> fields,
			HashMap<String, ByteIterator> result) {
		StringToStringMap queryResult = executeReadQuery(bucketName, key);
		if (queryResult == null) {
			return ERROR;
		}
		if (fields != null) {
			this.copyRequestedFieldsToResultMap(fields, queryResult, result);
		} else {
			this.copyAllFieldsToResultMap(queryResult, result);
		}
		return OK;
	}

	@Override
	public int scan(String table, String startkey, int recordcount,
			Set<String> fields, Vector<HashMap<String, ByteIterator>> result) {
		//TODO: implement
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public int update(String bucketName, String key,
			HashMap<String, ByteIterator> values) {
		StringToStringMap queryResult = this.executeReadQuery(bucketName, key);
		if(queryResult == null)
			return ERROR;
		for(String fieldToUpdate: values.keySet()){
			ByteIterator newValue = values.get(fieldToUpdate);
			queryResult.put(fieldToUpdate, newValue);
		}
		return this.executeWriteQuery(bucketName, key, queryResult);
	}

	@Override
	public int insert(String bucketName, String key,
			HashMap<String, ByteIterator> values) {
		StringToStringMap dataToInsert = new StringToStringMap(values);
		return this.executeWriteQuery(bucketName, key, dataToInsert);
	}

	@Override
	public int delete(String bucketName, String key) {
		try {
			Bucket bucket = this.client.fetchBucket(bucketName).execute();
			DeleteObject delObj = bucket.delete(key);
			delObj.rw(DEFAULT_DELETE_QUORA).execute();
		} catch (RiakRetryFailedException e) {
			return ERROR;
		} catch (RiakException e) {
			return ERROR;
		}
		return OK;
	}
}