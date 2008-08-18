/*******************************************************************************
 * Copyright (c) 2008 Vlad Dumitrescu and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at 
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Vlad Dumitrescu
 *******************************************************************************/
package org.erlide.runtime.backend;

import java.util.List;

import org.osgi.service.prefs.Preferences;

public class RuntimeInfo extends InfoElement {

	private static final String INSTALLATION = "installation";
	private static final String NODENAME = "nodeName";
	private static final String COOKIE = "cookie";
	private static final String ARGS = "args";
	private static final String WORKING_DIR = "workingDir";
	private static final String MANAGED = "managed";
	private static final String ERLIDE = "erlide";

	private static final String INSTALLATION_MARKER = "*INSTALLATION*";

	private String installation = "";
	private String args = "";
	private String cookie = "";
	private String nodeName = "";
	private String workingDir = "";
	private boolean managed; // will it be started/stopped by us?
	private boolean erlide; // will erlide code be installed?

	public RuntimeInfo() {
		super();
		getCodePath().add(INSTALLATION_MARKER);
	}

	@Override
	public void store(Preferences root) {
		super.store(root);
		Preferences node = root.node(getName());
		node.put(INSTALLATION, installation);
		node.put(NODENAME, getNodeName());
		node.put(COOKIE, cookie);
		node.put(ARGS, args);
		node.put(WORKING_DIR, workingDir);
		node.put(MANAGED, Boolean.toString(managed));
		node.put(ERLIDE, Boolean.toString(erlide));
	}

	@Override
	public void load(Preferences node) {
		super.load(node);
		installation = node.get(INSTALLATION, "");
		nodeName = node.get(NODENAME, "");
		cookie = node.get(COOKIE, "");
		args = node.get(ARGS, "");
		workingDir = node.get(WORKING_DIR, "");
		managed = node.getBoolean(MANAGED, false);
		erlide = node.getBoolean(ERLIDE, false);
	}

	public String getArgs() {
		return this.args;
	}

	public void setArgs(String args) {
		this.args = args;
	}

	public String getCookie() {
		if (cookie == null || cookie.length() == 0) {
			cookie = Cookie.retrieveCookie();
		}
		return this.cookie;
	}

	public void setCookie(String cookie) {
		this.cookie = cookie;
	}

	public String getNodeName() {
		return this.nodeName;
	}

	public void setNodeName(String nodeName) {
		this.nodeName = nodeName;
	}

	public String getInstallation() {
		return this.installation;
	}

	public void setInstallation(String installation) {
		this.installation = installation;
	}

	public boolean isManaged() {
		return this.managed;
	}

	public void setManaged(boolean managed) {
		this.managed = managed;
	}

	public boolean isErlide() {
		return this.erlide;
	}

	public void setErlide(boolean erlide) {
		this.erlide = erlide;
	}

	@Override
	public List<String> getPathA() {
		List<String> pathA = getPathA(INSTALLATION_MARKER);
		pathA.addAll(InstallationInfoManager.getDefault().getElement(
				getInstallation()).getPathA());
		return pathA;
	}

	@Override
	public List<String> getPathZ() {
		List<String> pathZ = InstallationInfoManager.getDefault().getElement(
				getInstallation()).getPathZ();
		pathZ.addAll(getPathZ(INSTALLATION_MARKER));
		return pathZ;
	}

	public String getWorkingDir() {
		return (workingDir == null || workingDir.length() == 0) ? "."
				: workingDir;
	}

	public void setWorkingDir(String workingDir) {
		this.workingDir = workingDir;
	}

	@Override
	public String toString() {
		return String.format("Runtime<%s (%s)>", getName(), getInstallation());
	}

	@Override
	public String getCmdLine() {
		String cmd = InstallationInfoManager.getDefault().getElement(
				getInstallation()).getCmdLine();
		cmd += "-name " + BackendManager.buildNodeName(getNodeName())
				+ " -setcookie " + getCookie();
		return cmd;
	}

}