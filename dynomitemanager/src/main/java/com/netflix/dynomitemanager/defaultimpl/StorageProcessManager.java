/**
 * Copyright 2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.netflix.dynomitemanager.defaultimpl;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.netflix.dynomitemanager.InstanceState;
import com.netflix.dynomitemanager.sidecore.storage.IStorageProxy;
import com.netflix.dynomitemanager.sidecore.utils.Sleeper;

/**
 * Start or stop the storage engine, such as Redis or Memcached.
 */
@Singleton
public class StorageProcessManager {
    private static final Logger logger = LoggerFactory.getLogger(StorageProcessManager.class);
    private static final String SUDO_STRING = "/usr/bin/sudo";
    private static final int SCRIPT_EXECUTE_WAIT_TIME_MS = 5000;
    private final Sleeper sleeper;
    private final InstanceState instanceState;
    private final IStorageProxy storageProxy;

    @Inject
    public StorageProcessManager(Sleeper sleeper, InstanceState instanceState, IStorageProxy storageProxy) {
	this.sleeper = sleeper;
	this.instanceState = instanceState;
	this.storageProxy = storageProxy;
    }

    /**
     * Start the storage engine (Redis, Memcached).
     * 
     * @throws IOException
     */
    public void start() throws IOException {
	logger.info(String.format("Starting Storage process"));

	List<String> command = Lists.newArrayList();
	if (!"root".equals(System.getProperty("user.name"))) {
	    command.add(SUDO_STRING);
	    command.add("-n");
	    command.add("-E");
	}
	command.addAll(getStartCommand());

	ProcessBuilder startStorage = new ProcessBuilder(command);

	startStorage.directory(new File("/"));
	startStorage.redirectErrorStream(true);
	Process starter = startStorage.start();

	try {
	    sleeper.sleepQuietly(SCRIPT_EXECUTE_WAIT_TIME_MS);
	    int code = starter.exitValue();
	    if (code == 0) {
		logger.info("Storage process has been started");
		instanceState.setStorageProxyAlive(true);
	    } else {
		logger.error("Unable to start Storage process. Error code: {}", code);
	    }

	    logProcessOutput(starter);
	} catch (Exception e) {
	    logger.warn("Starting Storage process has an error", e);
	}
    }

    protected List<String> getStartCommand() {
	List<String> startCmd = new LinkedList<String>();
	for (String param : storageProxy.getStartupScript().split(" ")) {
	    if (StringUtils.isNotBlank(param))
		startCmd.add(param);
	}
	return startCmd;
    }

    void logProcessOutput(Process p) {
	try {
	    final String stdOut = readProcessStream(p.getInputStream());
	    final String stdErr = readProcessStream(p.getErrorStream());
	    logger.info("std_out: {}", stdOut);
	    logger.info("std_err: {}", stdErr);
	} catch (IOException ioe) {
	    logger.warn("Failed to read the std out/err streams", ioe);
	}
    }

    String readProcessStream(InputStream inputStream) throws IOException {
	final byte[] buffer = new byte[512];
	final ByteArrayOutputStream baos = new ByteArrayOutputStream(buffer.length);
	int cnt;
	while ((cnt = inputStream.read(buffer)) != -1)
	    baos.write(buffer, 0, cnt);
	return baos.toString();
    }

    /**
     * Stop the storage engine (Redis, Memcached).
     * 
     * @throws IOException
     */
    public void stop() throws IOException {
	logger.info("Stopping storage process...");
	List<String> command = Lists.newArrayList();
	if (!"root".equals(System.getProperty("user.name"))) {
	    command.add(SUDO_STRING);
	    command.add("-n");
	    command.add("-E");
	}
	for (String param : storageProxy.getStopScript().split(" ")) {
	    if (StringUtils.isNotBlank(param))
		command.add(param);
	}
	ProcessBuilder stopStorage = new ProcessBuilder(command);
	stopStorage.directory(new File("/"));
	stopStorage.redirectErrorStream(true);
	// Run the storage engine's stop command
	Process stopper = stopStorage.start();

	sleeper.sleepQuietly(SCRIPT_EXECUTE_WAIT_TIME_MS);
	try {
	    int code = stopper.exitValue();
	    if (code == 0) {
		logger.info("Storage process has been stopped");
		instanceState.setStorageProxyAlive(false);
	    } else {
		logger.error("Unable to stop storage process. Error code: {}", code);
		logProcessOutput(stopper);
	    }
	} catch (Exception e) {
	    logger.warn("Could not shut down storage process correctly: ", e);
	}
    }
}
