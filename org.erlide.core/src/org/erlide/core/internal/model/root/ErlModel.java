/*******************************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.erlide.core.internal.model.root;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IPathVariableChangeEvent;
import org.eclipse.core.resources.IPathVariableChangeListener;
import org.eclipse.core.resources.IPathVariableManager;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.ISafeRunnable;
import org.eclipse.core.runtime.SafeRunner;
import org.erlide.core.CoreScope;
import org.erlide.core.ErlangCore;
import org.erlide.core.common.CommonUtils;
import org.erlide.core.internal.model.erlang.ErlModule;
import org.erlide.core.model.erlang.FunctionRef;
import org.erlide.core.model.erlang.IErlFunction;
import org.erlide.core.model.erlang.IErlModule;
import org.erlide.core.model.root.ErlModelException;
import org.erlide.core.model.root.IErlElement;
import org.erlide.core.model.root.IErlElementDelta;
import org.erlide.core.model.root.IErlElementVisitor;
import org.erlide.core.model.root.IErlFolder;
import org.erlide.core.model.root.IErlModel;
import org.erlide.core.model.root.IErlModelChangeListener;
import org.erlide.core.model.root.IErlParser;
import org.erlide.core.model.root.IErlProject;
import org.erlide.core.model.root.IOpenable;
import org.erlide.core.model.root.IParent;
import org.erlide.core.model.root.IWorkingCopy;
import org.erlide.core.model.util.ElementChangedEvent;
import org.erlide.core.model.util.ErlangFunction;
import org.erlide.core.model.util.ErlideUtil;
import org.erlide.core.model.util.IElementChangedListener;
import org.erlide.core.model.util.PluginUtils;
import org.erlide.jinterface.ErlLogger;
import org.osgi.service.prefs.BackingStoreException;

import com.ericsson.otp.erlang.OtpErlangList;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangString;
import com.ericsson.otp.erlang.OtpErlangTuple;
import com.google.common.base.Predicate;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

/**
 * Implementation of
 * <code>IErlModel<code>. The Erlang Model maintains a cache of
 * active <code>IErlProject</code>s in a workspace. A Erlang Model is specific
 * to a workspace. To retrieve a workspace's model, use the
 * <code>#getErlangModel(IWorkspace)</code> method.
 * 
 * @see IErlModel
 */
public class ErlModel extends Openable implements IErlModel {

    private final ArrayList<IErlModelChangeListener> fListeners = new ArrayList<IErlModelChangeListener>(
            5);

    private final IPathVariableChangeListener fPathVariableChangeListener;

    /**
     * Turns delta firing on/off. By default it is on.
     */
    protected boolean fFire = true;

    /**
     * Queue of reconcile deltas on working copies that have yet to be fired.
     * This is a table form IWorkingCopy to IErlElementDelta
     */
    private final HashMap<IWorkingCopy, IErlElementDelta> reconcileDeltas = new HashMap<IWorkingCopy, IErlElementDelta>();

    /**
     * Listeners for element changes
     */
    protected List<IElementChangedListener> elementChangedListeners = new ArrayList<IElementChangedListener>();

    /**
     * Queue of deltas created explicitly by the model that have yet to be
     * fired.
     */
    private final List<IErlElementDelta> erlModelDeltas = Collections
            .synchronizedList(new ArrayList<IErlElementDelta>());

    public static final int DEFAULT_CHANGE_EVENT = 0; // must not collide with

    public static boolean verbose = Boolean.getBoolean("erlide.model.verbose");

    public enum External {
        EXTERNAL_MODULES, EXTERNAL_INCLUDES
    }

    OtpErlangList fCachedPathVars = null;

    private final IErlParser parser;

    /**
     * Constructs a new Erlang Model on the given workspace. Note that only one
     * instance of ErlModel handle should ever be created. One should only
     * indirect through ErlModelManager#getErlangModel() to get access to it.
     * 
     * @exception Error
     *                if called more than once
     */
    ErlModel() {
        super(null, ""); //$NON-NLS-1$
        parser = new ErlParser();
        final IWorkspace workspace = ResourcesPlugin.getWorkspace();
        final IPathVariableManager pvm = workspace.getPathVariableManager();
        fPathVariableChangeListener = new PathVariableChangeListener();
        pvm.addChangeListener(fPathVariableChangeListener);
        final IResourceChangeListener listener = new ResourceChangeListener();
        workspace.addResourceChangeListener(listener);
    }

    @Override
    protected boolean buildStructure(final IProgressMonitor pm) {
        setChildren(null);
        // determine my children
        final IProject[] projects = ResourcesPlugin.getWorkspace().getRoot()
                .getProjects();
        for (final IProject project : projects) {
            if (ErlideUtil.hasErlangNature(project)) {
                if (getErlangProject(project) == null) {
                    addChild(makeErlangProject(project));
                }
            }
        }

        return true;
    }

    public static final ErlModelCache getErlModelCache() {
        return ErlModelCache.getDefault();
    }

    /**
     * @see IErlModel
     */
    public void copy(final IErlElement[] elements,
            final IErlElement[] containers, final IErlElement[] siblings,
            final String[] renamings, final boolean force,
            final IProgressMonitor monitor) throws ErlModelException {
        // if (elements != null && elements.length > 0 && elements[0] != null
        // && elements[0].getElementType() < IErlElement.TYPE)
        // {
        // runOperation(new CopyResourceElementsOperation(elements, containers,
        // force),
        // elements,
        // siblings, renamings, monitor);
        // } else
        // {
        // runOperation(new CopyElementsOperation(elements, containers, force),
        // elements,
        // siblings, renamings, monitor);
        // }
    }

    /**
     * @see IErlModel
     */
    public void delete(final IErlElement[] elements, final boolean force,
            final IProgressMonitor monitor) throws ErlModelException {
        // if (elements != null && elements.length > 0 && elements[0] != null
        // && elements[0].getElementType() < IErlElement.TYPE)
        // {
        // new DeleteResourceElementsOperation(elements, force)
        // .runOperation(monitor);
        // }
        // else
        // {
        // new DeleteElementsOperation(elements, force).runOperation(monitor);
        // }
    }

    @Override
    public boolean equals(final Object o) {
        if (!(o instanceof ErlModel)) {
            return false;
        }
        return super.equals(o);
    }

    /**
     * @see IErlElement
     */
    public Kind getKind() {
        return Kind.MODEL;
    }

    /**
     * @see ErlElement#getHandleMemento()
     */
    public String getHandleMemento() {
        return getName();
    }

    /**
     * Returns the <code>char</code> that marks the start of this handles
     * contribution to a memento.
     */
    protected char getHandleMementoDelimiter() {
        Assert.isTrue(false, "Should not be called"); //$NON-NLS-1$
        return 0;
    }

    /**
     * @see IErlModel
     */
    public IErlProject getErlangProject(final IProject project) {
        if (!project.isAccessible()) {
            return null;
        }
        final IErlElement e = getChildWithResource(project);
        if (e instanceof IErlProject) {
            return (IErlProject) e;
        }
        return makeErlangProject(project);
    }

    public IErlProject makeErlangProject(final IProject project) {
        final IErlProject ep = new ErlProject(project, this);
        ErlLogger.debug("makeErlangProject " + ep);
        addChild(ep);
        final ErlModelCache cache = getModelCache();
        cache.newProjectCreated();
        return ep;
    }

    /**
     * @see IErlModel
     */
    public Collection<IErlProject> getErlangProjects() throws ErlModelException {
        final Collection<IErlElement> list = getChildrenOfKind(Kind.PROJECT);
        final Collection<IErlProject> result = Lists.newArrayList();
        for (final IErlElement e : list) {
            result.add((IErlProject) e);
        }
        return result;
    }

    /*
     * @see IErlElement
     */
    @Override
    public IResource getResource() {
        return ResourcesPlugin.getWorkspace().getRoot();
    }

    // /**
    // * @see IOpenable
    // */
    // @Override
    // public IResource getUnderlyingResource() {
    // return null;
    // }

    /**
     * Returns the workbench associated with this object.
     */
    public IWorkspace getWorkspace() {
        return ResourcesPlugin.getWorkspace();
    }

    /**
     * @see IErlModel
     */
    public void move(final IErlElement[] elements,
            final IErlElement[] containers, final IErlElement[] siblings,
            final String[] renamings, final boolean force,
            final IProgressMonitor monitor) throws ErlModelException {
        // if (elements != null && elements.length > 0 && elements[0] != null
        // && elements[0].getElementType() < IErlElement.TYPE)
        // {
        // runOperation(new MoveResourceElementsOperation(elements,
        // containers, force), elements, siblings, renamings, monitor);
        // }
        // else
        // {
        // runOperation(
        // new MoveElementsOperation(elements, containers, force),
        // elements, siblings, renamings, monitor);
        // }
    }

    /**
     * @see IErlModel
     */
    public void rename(final IErlElement[] elements,
            final IErlElement[] destinations, final String[] renamings,
            final boolean force, final IProgressMonitor monitor)
            throws ErlModelException {
        // MultiOperation op;
        // if (elements != null && elements.length > 0 && elements[0] != null
        // && elements[0].getElementType() < IErlElement.TYPE)
        // {
        // op = new RenameResourceElementsOperation(elements, destinations,
        // renamings, force);
        // }
        // else
        // {
        // op = new RenameElementsOperation(elements, destinations, renamings,
        // force);
        // }
        //
        // op.runOperation(monitor);
    }

    // /**
    // * Configures and runs the <code>MultiOperation</code>.
    // */
    // protected void runOperation(MultiOperation op, IErlElement[] elements,
    // IErlElement[] siblings, String[] renamings,
    // IProgressMonitor monitor) throws ErlModelException
    // {
    // op.setRenamings(renamings);
    // if (siblings != null)
    // {
    // for (int i = 0; i < elements.length; i++)
    // {
    // op.setInsertBefore(elements[i], siblings[i]);
    // }
    // }
    // op.runOperation(monitor);
    // }

    /**
     * @private Debugging purposes
     */
    @Override
    protected void toStringInfo(final int tab, final StringBuilder buffer,
            final Object info) {
        buffer.append(tabString(tab));
        buffer.append("Erlang Model"); //$NON-NLS-1$
        if (info == null) {
            buffer.append(" (not open)"); //$NON-NLS-1$
        }
    }

    /**
     * Helper method - returns the targeted item (IResource if internal or
     * java.io.File if external), or null if unbound Internal items must be
     * referred to using container relative paths.
     */
    public static Object getTarget(final IContainer container,
            final IPath path, final boolean checkResourceExistence) {

        if (path == null) {
            return null;
        }

        // lookup - inside the container
        if (path.getDevice() == null) { // container relative paths should not
            // contain a device
            // (see http://dev.eclipse.org/bugs/show_bug.cgi?id=18684)
            // (case of a workspace rooted at d:\ )
            final IResource resource = container.findMember(path);
            if (resource != null) {
                if (!checkResourceExistence || resource.exists()) {
                    return resource;
                }
                return null;
            }
        }

        // if path is relative, it cannot be an external path
        // (see http://dev.eclipse.org/bugs/show_bug.cgi?id=22517)
        if (!path.isAbsolute()) {
            return null;
        }

        // lookup - outside the container
        final File externalFile = new File(path.toOSString());
        if (!checkResourceExistence) {
            return externalFile;
        }
        if (externalFile.exists()) {
            return externalFile;
        }
        return null;
    }

    public void notifyChange(final IErlElement element) {
        if (System.getProperty("erlide.model.notify") != null) {
            ErlLogger.debug("^> notifying change of " + element.getName());
            ErlLogger.debug("   caller = " + getStack());
        }
        for (int i = 0; i < fListeners.size(); i++) {
            fListeners.get(i).elementChanged(element);
        }
    }

    private static synchronized String getStack() {
        final StringBuilder result = new StringBuilder();
        final StackTraceElement[] st = Thread.currentThread().getStackTrace();
        for (final StackTraceElement el : st) {
            result.append("      ").append(el.toString()).append("\n");
        }
        return result.toString();
    }

    public void addModelChangeListener(final IErlModelChangeListener listener) {
        if (!fListeners.contains(listener)) {
            fListeners.add(listener);
        }
    }

    public void removeModelChangeListener(final IErlModelChangeListener listener) {
        fListeners.remove(listener);
    }

    @Override
    protected void closing(final Object info) throws ErlModelException {
        final IPathVariableManager pvm = ResourcesPlugin.getWorkspace()
                .getPathVariableManager();
        pvm.removeChangeListener(fPathVariableChangeListener);
    }

    public IErlElement findElement(final IResource rsrc) {
        return findElement(rsrc, false);
    }

    public IErlElement findElement(final IResource rsrc,
            final boolean openElements) {
        if (rsrc == null) {
            return null;
        }
        final IPath path = rsrc.getFullPath();
        IParent p = this;
        for (final String segment : path.segments()) {
            IErlElement c = p.getChildWithResource(rsrc);
            if (c != null) {
                return c;
            }
            c = p.getChildNamed(segment);
            if (c == null) {
                return null;
            }
            if (openElements) {
                if (c instanceof IOpenable) {
                    final IOpenable o = (IOpenable) c;
                    try {
                        o.open(null);
                    } catch (final ErlModelException e) {
                        e.printStackTrace();
                        return null;
                    }
                }
            }
            final IResource resource = c.getResource();
            if (resource != null && resource.equals(rsrc)) {
                return c;
            }
            p = (IParent) c;
        }
        return null;
    }

    public IErlElement innermostThat(final IErlElement el,
            final Predicate<IErlElement> firstThat) {
        if (el instanceof IParent) {
            final IParent p = (IParent) el;
            try {
                for (final IErlElement child : p.getChildren()) {
                    final IErlElement e2 = innermostThat(child, firstThat);
                    if (e2 != null) {
                        return e2;
                    }
                }
            } catch (final ErlModelException e) {
            }
        }
        if (firstThat.apply(el)) {
            return el;
        }
        return null;
    }

    public IErlModule findModule(final IFile file) {
        try {
            open(null);
        } catch (final ErlModelException e) {
        }
        IErlElement element = findElement(file, false);
        if (element == null) {
            element = findElement(file, true);
        }
        if (element == null) {
            return (IErlModule) create(file);
        }
        return (IErlModule) element;
    }

    public IErlProject findProject(final IProject project) {
        final IErlElement e = findElement(project);
        if (e == null) {
            return null;
        }
        return (IErlProject) e;
    }

    public IErlModule findModule(final String name) throws ErlModelException {
        return ErlModel.findModuleFromProject(null, name, null, false, false,
                IErlModel.Scope.ALL_PROJECTS);
    }

    public IErlModule findModuleIgnoreCase(final String name)
            throws ErlModelException {
        return ErlModel.findModuleFromProject(null, name, null, true, false,
                IErlModel.Scope.ALL_PROJECTS);
    }

    public IErlProject createOtpProject(final IProject project)
            throws CoreException, BackingStoreException {
        final IPath location = project.getLocation();

        final IErlProject p = getErlangProject(project);

        final IFile file = project.getFile(".");
        if (!file.isLinked()) {
            file.createLink(location, IResource.NONE, null);
        }

        Collection<IPath> dirs;
        dirs = findOtpSourceDirs(new File(location.toString()));
        p.setSourceDirs(dirs);
        dirs = findOtpIncludeDirs(new File(location.toString()));
        p.setIncludeDirs(dirs);
        p.open(null);
        return p;
    }

    private static Collection<IPath> findOtpSourceDirs(final File file) {
        final List<IPath> result = Lists.newArrayList();
        return result;
    }

    private static Collection<IPath> findOtpIncludeDirs(final File file) {
        final List<IPath> result = Lists.newArrayList();
        return result;
    }

    public final IErlProject newProject(final String name, final String path)
            throws ErlModelException {
        final IWorkspace ws = ResourcesPlugin.getWorkspace();
        final IProject project = ws.getRoot().getProject(name);
        try {
            if (!project.exists()) {
                project.create(null);
                project.open(null);
                final IProjectDescription description = project
                        .getDescription();
                description.setNatureIds(new String[] { ErlangCore.NATURE_ID });
                description.setName(name);
                project.setDescription(description, null);
            }
            if (!project.isOpen()) {
                project.open(null);
            }
            return makeErlangProject(project);
        } catch (final CoreException e) {
            throw new ErlModelException(e);
        }
    }

    public final void accept(final IErlElement element,
            final IErlElementVisitor visitor, final EnumSet<AcceptFlags> flags,
            final IErlElement.Kind leafKind) throws ErlModelException {
        if (element.getKind() == leafKind) {
            visitor.visit(element);
        } else {
            boolean visitChildren = true;
            if (!flags.contains(AcceptFlags.LEAFS_ONLY)
                    && !flags.contains(AcceptFlags.CHILDREN_FIRST)) {
                visitChildren = visitor.visit(element);
            }
            if (visitChildren && element instanceof IParent) {
                final IParent parent = (IParent) element;
                for (final IErlElement child : parent.getChildren()) {
                    accept(child, visitor, flags, leafKind);
                }
            }
            if (!flags.contains(AcceptFlags.LEAFS_ONLY)
                    && flags.contains(AcceptFlags.CHILDREN_FIRST)) {
                visitor.visit(element);
            }
        }
    }

    private final class PathVariableChangeListener implements
            IPathVariableChangeListener {

        public void pathVariableChanged(final IPathVariableChangeEvent event) {
            fCachedPathVars = null;
            getErlModelCache().pathVarsChanged();
            try {
                // broadcast this change to projects, they need to clear their
                // caches
                for (final IErlProject project : getErlangProjects()) {
                    ((ErlProject) project).pathVarsChanged();
                }
            } catch (final ErlModelException e) {
            }
        }

    }

    public OtpErlangList getPathVars() {
        if (fCachedPathVars == null) {
            final IPathVariableManager pvm = ResourcesPlugin.getWorkspace()
                    .getPathVariableManager();
            final String[] names = pvm.getPathVariableNames();
            final OtpErlangObject[] objects = new OtpErlangObject[names.length];
            for (int i = 0; i < names.length; i++) {
                final String name = names[i];
                final String value = PluginUtils.getPVMValue(pvm, name)
                        .toOSString();
                objects[i] = new OtpErlangTuple(new OtpErlangObject[] {
                        new OtpErlangString(name), new OtpErlangString(value) });
            }
            fCachedPathVars = new OtpErlangList(objects);
        }
        return fCachedPathVars;
    }

    public IErlFunction findFunction(final FunctionRef r)
            throws ErlModelException {
        final IErlModule module = findModule(r.module);
        module.open(null);
        return module.findFunction(new ErlangFunction(r.function, r.arity));
    }

    public IErlModule findModule(final String moduleName,
            final String modulePath) throws ErlModelException {
        return ErlModel.findModuleFromProject(null, moduleName, modulePath,
                false, true, IErlModel.Scope.ALL_PROJECTS);
    }

    public IErlModule findInclude(final String includeName,
            final String includePath) throws ErlModelException {
        return ErlModel.findIncludeFromProject(null, includeName, includePath,
                false, false, IErlModel.Scope.ALL_PROJECTS);
    }

    private static volatile ErlModel fgErlangModel;

    public static final IErlModel getErlangModel() {
        if (fgErlangModel == null) {
            fgErlangModel = new ErlModel();
            fgErlangModel.buildStructure(null);
        }
        return fgErlangModel;
    }

    /**
     * Adds the given listener for changes to Erlang elements. Has no effect if
     * an identical listener is already registered. After completion of this
     * method, the given listener will be registered for exactly the specified
     * events. If they were previously registered for other events, they will be
     * deregistered.
     * <p>
     * Once registered, a listener starts receiving notification of changes to
     * Erlang elements in the model. The listener continues to receive
     * notifications until it is replaced or removed.
     * </p>
     * <p>
     * Listeners can listen for several types of event as defined in
     * <code>ElementChangeEvent</code>. Clients are free to register for any
     * number of event types however if they register for more than one, it is
     * their responsibility to ensure they correctly handle the case where the
     * same Erlang element change shows up in multiple notifications. Clients
     * are guaranteed to receive only the events for which they are registered.
     * </p>
     * 
     * @param listener
     *            the listener
     * @param eventMask
     *            the bit-wise OR of all event types of interest to the listener
     * @see IElementChangedListener
     * @see ElementChangedEvent
     * @see #removeElementChangedListener(IElementChangedListener)
     */
    public void addElementChangedListener(
            final IElementChangedListener listener, final int eventMask) {
        // getDefault().addElementChangedListener(listener, eventMask);
    }

    /**
     * Removes the given element changed listener. Has no affect if an identical
     * listener is not registered.
     * 
     * @param listener
     *            the listener
     */
    public void removeElementChangedListener(
            final IElementChangedListener listener) {
        // getDefault().removeElementChangedListener(listener);
    }

    /**
     * Adds the given listener for changes to Erlang elements. Has no effect if
     * an identical listener is already registered.
     * 
     * This listener will only be notified during the POST_CHANGE resource
     * change notification and any reconcile operation (POST_RECONCILE). For
     * finer control of the notification, use
     * <code>addElementChangedListener(IElementChangedListener,int)</code>,
     * which allows to specify a different eventMask.
     * 
     * @param listener
     *            the listener
     * @see ElementChangedEvent
     */
    public void addElementChangedListener(final IElementChangedListener listener) {
        addElementChangedListener(listener, ElementChangedEvent.POST_CHANGE);
        // | ElementChangedEvent.POST_RECONCILE);
    }

    private static Map<Object, IErlModule> moduleMap = new HashMap<Object, IErlModule>();
    private static Map<IErlModule, Object> mapModule = new HashMap<IErlModule, Object>();

    public IErlModule getModuleFromFile(final IParent parent,
            final String name, final String initialText, final String path,
            final String key) {
        IErlModule m = moduleMap.get(key);
        if (m == null) {
            final IParent parent2 = parent == null ? this : parent;
            final boolean useCache = path != null && path.length() > 0;
            m = new ErlModule(parent2, name, initialText, null, path, useCache);
            if (key != null) {
                moduleMap.put(key, m);
                mapModule.put(m, key);
            }
        }
        return m;
    }

    public void removeModule(final IErlModule module) {
        final Object key = mapModule.get(module);
        if (key != null) {
            mapModule.remove(module);
            moduleMap.remove(key);
        }
        ErlModel.getErlModelCache().removeModule(module);
    }

    public IErlModule getModuleFromText(final IParent parent,
            final String name, final String initialText, final String key) {
        return getModuleFromFile(parent, name, initialText, "", key);
    }

    public void putEdited(final String path, final IErlModule module) {
        ErlModel.getErlModelCache().putEdited(path, module);
    }

    public void fire(final int eventType) {
        fire(null, eventType);
    }

    /**
     * Fire Model deltas, flushing them after the fact. If the firing mode has
     * been turned off, this has no effect.
     */
    private void fire(final IErlElementDelta customDeltas, final int eventType) {
        if (fFire) {
            IErlElementDelta deltaToNotify;
            if (customDeltas == null) {
                deltaToNotify = mergeDeltas(erlModelDeltas);
            } else {
                deltaToNotify = customDeltas;
            }

            final IElementChangedListener[] listeners;
            final int listenerCount;
            final int[] listenerMask;
            // Notification
            synchronized (elementChangedListeners) {
                listeners = new IElementChangedListener[elementChangedListeners
                        .size()];
                elementChangedListeners.toArray(listeners);
                listenerCount = listeners.length;
                listenerMask = null;
            }

            switch (eventType) {
            case DEFAULT_CHANGE_EVENT:
                // firePreAutoBuildDelta(deltaToNotify, listeners, listenerMask,
                // listenerCount);
                firePostChangeDelta(deltaToNotify, listeners, listenerMask,
                        listenerCount);
                fireReconcileDelta(listeners, listenerMask, listenerCount);
                break;
            // case ElementChangedEvent.PRE_AUTO_BUILD :
            // firePreAutoBuildDelta(deltaToNotify, listeners, listenerMask,
            // listenerCount);
            // break;
            case ElementChangedEvent.POST_CHANGE:
                firePostChangeDelta(deltaToNotify, listeners, listenerMask,
                        listenerCount);
                fireReconcileDelta(listeners, listenerMask, listenerCount);
                break;
            case ElementChangedEvent.POST_RECONCILE:
                fireReconcileDelta(listeners, listenerMask, listenerCount);
                break;
            case ElementChangedEvent.POST_SHIFT:
                fireShiftEvent(deltaToNotify, listeners, listenerMask,
                        listenerCount);
                return;
            }
        }
    }

    private void firePostChangeDelta(final IErlElementDelta deltaToNotify,
            final IElementChangedListener[] listeners,
            final int[] listenerMask, final int listenerCount) {

        // post change deltas
        if (verbose) {
            System.out
                    .println("FIRING POST_CHANGE Delta [" + Thread.currentThread() + "]:"); //$NON-NLS-1$//$NON-NLS-2$
            System.out
                    .println(deltaToNotify == null ? "<NONE>" : deltaToNotify.toString()); //$NON-NLS-1$
        }
        if (deltaToNotify != null) {
            // flush now so as to keep listener reactions to post their own
            // deltas for
            // subsequent iteration
            flush();
            notifyListeners(deltaToNotify, ElementChangedEvent.POST_CHANGE,
                    listeners, listenerMask, listenerCount);
        }
    }

    private void fireReconcileDelta(final IElementChangedListener[] listeners,
            final int[] listenerMask, final int listenerCount) {
        final IErlElementDelta deltaToNotify = mergeDeltas(reconcileDeltas
                .values());
        if (verbose) {
            System.out
                    .println("FIRING POST_RECONCILE Delta [" + Thread.currentThread() + "]:"); //$NON-NLS-1$//$NON-NLS-2$
            System.out
                    .println(deltaToNotify == null ? "<NONE>" : deltaToNotify.toString()); //$NON-NLS-1$
        }
        if (deltaToNotify != null) {
            // flush now so as to keep listener reactions to post their own
            // deltas for
            // subsequent iteration
            reconcileDeltas.clear();
            notifyListeners(deltaToNotify, ElementChangedEvent.POST_RECONCILE,
                    listeners, listenerMask, listenerCount);
        }
    }

    private void fireShiftEvent(final IErlElementDelta deltaToNotify,
            final IElementChangedListener[] listeners,
            final int[] listenerMask, final int listenerCount) {

        // post change deltas
        if (verbose) {
            System.out
                    .println("FIRING POST_SHIFT event [" + Thread.currentThread() + "]:"); //$NON-NLS-1$//$NON-NLS-2$
            System.out
                    .println(deltaToNotify == null ? "<NONE>" : deltaToNotify.toString()); //$NON-NLS-1$
        }
        if (deltaToNotify != null) {
            flush();
            notifyListeners(deltaToNotify, ElementChangedEvent.POST_SHIFT,
                    listeners, listenerMask, listenerCount);
        }
    }

    private IErlElementDelta mergeDeltas(
            final Collection<IErlElementDelta> deltas) {

        synchronized (deltas) {
            if (deltas.size() == 0) {
                return null;
            }
            if (deltas.size() == 1) {
                return deltas.iterator().next();
            }
            if (deltas.size() <= 1) {
                return null;
            }

            final Iterator<IErlElementDelta> iterator = deltas.iterator();
            final IErlElement cRoot = getErlangModel();
            final ErlElementDelta rootDelta = new ErlElementDelta(0, 0, cRoot);
            boolean insertedTree = false;
            while (iterator.hasNext()) {
                final ErlElementDelta delta = (ErlElementDelta) iterator.next();
                final IErlElement element = delta.getElement();
                if (cRoot.equals(element)) {
                    final IErlElementDelta[] children = delta
                            .getChildren(IErlElementDelta.ALL);
                    for (final IErlElementDelta element0 : children) {
                        final ErlElementDelta projectDelta = (ErlElementDelta) element0;
                        rootDelta.insertDeltaTree(projectDelta.getElement(),
                                projectDelta);
                        insertedTree = true;
                    }
                    final IResourceDelta[] resourceDeltas = delta
                            .getResourceDeltas();
                    if (resourceDeltas != null) {
                        for (final IResourceDelta element0 : resourceDeltas) {
                            rootDelta.addResourceDelta(element0);
                            insertedTree = true;
                        }
                    }
                } else {
                    rootDelta.insertDeltaTree(element, delta);
                    insertedTree = true;
                }
            }
            if (insertedTree) {
                return rootDelta;
            }
            return null;
        }
    }

    /**
     * Registers the given delta with this manager. This API is to be used to
     * registered deltas that are created explicitly by the C Model. Deltas
     * created as translations of <code>IResourceDeltas</code> are to be
     * registered with <code>#registerResourceDelta</code>.
     */
    public void registerModelDelta(final IErlElementDelta delta) {
        erlModelDeltas.add(delta);
    }

    /**
     * Flushes all deltas without firing them.
     */
    protected void flush() {
        erlModelDeltas.clear();
    }

    public void notifyListeners(final IErlElementDelta deltaToNotify,
            final int eventType, final IElementChangedListener[] listeners,
            final int[] listenerMask, final int listenerCount) {

        final ElementChangedEvent extraEvent = new ElementChangedEvent(
                deltaToNotify, eventType);
        for (int i = 0; i < listenerCount; i++) {
            if (listenerMask == null || (listenerMask[i] & eventType) != 0) {
                final IElementChangedListener listener = listeners[i];
                long start = -1;
                if (verbose) {
                    System.out
                            .print("Listener #" + (i + 1) + "=" + listener.toString());//$NON-NLS-1$//$NON-NLS-2$
                    start = System.currentTimeMillis();
                }
                // wrap callbacks with Safe runnable for subsequent listeners to
                // be called
                // when some are causing grief
                SafeRunner.run(new ISafeRunnable() {

                    public void handleException(final Throwable exception) {
                        // CCorePlugin.log(exception, "Exception occurred in
                        // listener of C
                        // element change notification"); //$NON-NLS-1$
                        ErlLogger.error(exception);
                    }

                    public void run() throws Exception {
                        listener.elementChanged(extraEvent);
                    }
                });
                if (verbose) {
                    System.out
                            .println(" -> " + (System.currentTimeMillis() - start) + "ms"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
    }

    public IErlElement create(final IResource resource, final IParent parent) {
        if (resource == null) {
            return null;
        }
        final IErlElement e = findElement(resource);
        if (e != null) {
            return e; // TODO or should this give an exception?
        }
        final int type = resource.getType();
        switch (type) {
        case IResource.PROJECT:
            return createProject((IProject) resource); // , parent);
        case IResource.FILE:
            return createFile((IFile) resource, parent);
        case IResource.FOLDER:
            return createFolder((IFolder) resource, parent);
        case IResource.ROOT:
            return createRoot((IWorkspaceRoot) resource);
        default:
            return null;
        }
        // TODO should we make Erlidemodelevents and fire them?
    }

    void remove(final IResource rsrc) {
        final IErlElement element = findElement(rsrc);
        if (element != null) {
            final IParent p = element.getParent();
            p.removeChild(element);
            if (element instanceof IOpenable) {
                final IOpenable openable = (IOpenable) element;
                try {
                    openable.close();
                } catch (final ErlModelException e) {
                    ErlLogger.error(e);
                }
            }
        }
        // TODO should we make Erlidemodelevents and fire them?
    }

    void change(final IResource rsrc, final IResourceDelta delta) {
        final IErlElement e = findElement(rsrc);
        if (e != null) {
            e.resourceChanged(delta);
        }
        // TODO should we make Erlidemodelevents and fire them?
    }

    /**
     * Returns the Erlang element corresponding to the given file, its project
     * being the given project. Returns <code>null</code> if unable to associate
     * the given file with a Erlang element.
     * 
     * <p>
     * The file must be one of:
     * <ul>
     * <li>a <code>.erl</code> file - the element returned is the corresponding
     * <code>IErlModule</code></li>
     * <li>a <code>.beam</code> file - the element returned is the corresponding
     * <code>IBeamFile</code></li>
     * </ul>
     * <p>
     * Creating a Erlang element has the side effect of creating and opening all
     * of the element's parents if they are not yet open.
     */
    public IErlElement createFile(final IFile file, IParent parent) {
        if (file == null) {
            return null;
        }
        if (parent == null) {
            final IContainer parentResource = file.getParent();
            if (parentResource != null) {
                final IErlElement element = findElement(parentResource);
                if (element instanceof IParent) {
                    parent = (IParent) element;
                }
            }
        }
        if (CommonUtils.isErlangFileContentFileName(file.getName())) {
            return createModuleFrom(file, parent);
        }
        return null;
    }

    public IErlFolder createFolder(final IFolder folder, final IParent parent) {
        if (folder == null) {
            return null;
        }
        final IErlFolder f = new ErlFolder(folder, parent);
        final IParent p = parent;
        if (p != null) {
            p.addChild(f);
        } else {
            // ErlLogger.warn("creating folder %s in null parent?!", folder
            // .getName());
        }
        return f;
    }

    public IErlModule createModuleFrom(final IFile file, final IParent parent) {
        if (file == null) {
            return null;
        }
        final String name = file.getName();
        if (CommonUtils.isErlangFileContentFileName(name)) {
            final IErlModule module = new ErlModule(parent, name, null, file,
                    null, true);
            if (parent != null) {
                parent.addChild(module);
            }
            return module;
        }
        return null;
    }

    /**
     * Returns the Erlang project corresponding to the given project.
     * <p>
     * Creating a Erlang Project has the side effect of creating and opening all
     * of the project's parents if they are not yet open.
     * <p>
     * Note that no check is done at this time on the existence or the Erlang
     * nature of this project.
     * 
     * @param project
     *            the given project
     * @return the Erlang project corresponding to the given project, null if
     *         the given project is null
     */
    public IErlProject createProject(final IProject project) {
        if (project == null) {
            return null;
        }
        return makeErlangProject(project);
    }

    /**
     * Returns the Erlang element corresponding to the given resource, or
     * <code>null</code> if unable to associate the given resource with a Erlang
     * element.
     * <p>
     * The resource must be one of:
     * <ul>
     * <li>a project - the element returned is the corresponding
     * <code>IErlProject</code></li>
     * <li>a <code>.Erlang</code> file - the element returned is the
     * corresponding <code>IErlModule</code></li>
     * <li>a <code>.class</code> file - the element returned is the
     * corresponding <code>IClassFile</code></li>
     * <li>a <code>.jar</code> file - the element returned is the corresponding
     * <code>IPackageFragmentRoot</code></li>
     * <li>a folder - the element returned is the corresponding
     * <code>IPackageFragmentRoot</code> or <code>IPackageFragment</code></li>
     * <li>the workspace root resource - the element returned is the
     * <code>IErlModel</code></li>
     * </ul>
     * <p>
     * Creating a Erlang element has the side effect of creating and opening all
     * of the element's parents if they are not yet open.
     * 
     * @param resource
     *            the given resource
     * @return the Erlang element corresponding to the given resource, or
     *         <code>null</code> if unable to associate the given resource with
     *         a Erlang element
     */
    public IErlElement create(final IResource resource) {
        IParent parent = null;
        final IContainer resourceParent = resource.getParent();
        if (resourceParent != null) {
            IErlElement element = findElement(resourceParent);
            if (element == null) {
                element = create(resourceParent);
            }
            if (element instanceof IParent) {
                parent = (IParent) element;
            }
        }
        return create(resource, parent);
    }

    /**
     * Returns the Erlang model.
     * 
     * @param root
     *            the given root
     * @return the Erlang model, or <code>null</code> if the root is null
     */
    private IErlModel createRoot(final IWorkspaceRoot root) {
        if (root == null) {
            return null;
        }
        return getErlangModel();
    }

    class ResourceChangeListener implements IResourceChangeListener {
        public void resourceChanged(final IResourceChangeEvent event) {
            final IResourceDelta rootDelta = event.getDelta();
            final ArrayList<IResource> added = Lists.newArrayList();
            final ArrayList<IResource> changed = Lists.newArrayList();
            final ArrayList<IResource> removed = Lists.newArrayList();
            final Map<IResource, IResourceDelta> changedDelta = Maps
                    .newHashMap();
            final IResourceDeltaVisitor visitor;
            if (event.getType() == IResourceChangeEvent.POST_CHANGE) {
                visitor = new IResourceDeltaVisitor() {
                    public boolean visit(final IResourceDelta delta) {
                        final IResource resource = delta.getResource();
                        if (verbose) {
                            ErlLogger.debug("delta " + delta.getKind()
                                    + " for " + resource.getLocation());
                        }
                        final boolean erlangFile = resource.getType() == IResource.FILE
                                && CommonUtils
                                        .isErlangFileContentFileName(resource
                                                .getName());
                        final boolean erlangProject = resource.getType() == IResource.PROJECT
                                && ErlideUtil
                                        .hasErlangNature((IProject) resource);
                        final boolean erlangFolder = resource.getType() == IResource.FOLDER;
                        // &&
                        // ErlideUtil.isOnSourcePathOrParentToFolderOnSourcePath((
                        // IFolder)
                        // resource);
                        if (erlangFile || erlangProject || erlangFolder) {
                            if (delta.getKind() == IResourceDelta.ADDED) {
                                added.add(resource);
                            }
                            if (delta.getKind() == IResourceDelta.CHANGED) {
                                changed.add(resource);
                                changedDelta.put(resource, delta);
                            }
                            if (delta.getKind() == IResourceDelta.REMOVED) {
                                removed.add(resource);
                            }
                        }
                        return !erlangFile;
                    }
                };
            } else if (event.getType() == IResourceChangeEvent.PRE_CLOSE) {
                visitor = new IResourceDeltaVisitor() {

                    public boolean visit(final IResourceDelta delta)
                            throws CoreException {
                        final IResource resource = delta.getResource();
                        final boolean erlangProject = resource.getType() == IResource.PROJECT
                                && ErlideUtil
                                        .hasErlangNature((IProject) resource);
                        if (erlangProject) {
                            removed.add(resource);
                        }
                        return false;
                    }
                };
                final IResource resource = event.getResource();
                final boolean erlangProject = resource.getType() == IResource.PROJECT
                        && ErlideUtil.hasErlangNature((IProject) resource);
                if (erlangProject) {
                    removed.add(resource);
                }
            } else {
                visitor = new IResourceDeltaVisitor() {

                    public boolean visit(final IResourceDelta delta)
                            throws CoreException {
                        return false;
                    }
                };
            }
            if (rootDelta != null) {
                try {
                    rootDelta.accept(visitor);
                } catch (final CoreException e) {
                    ErlLogger.warn(e);
                }
            }
            for (final IResource rsrc : added) {
                create(rsrc);
            }
            for (final IResource rsrc : changed) {
                change(rsrc, changedDelta.get(rsrc));
            }
            // make sure we don't dispose trees before leaves...
            Collections.sort(removed, new Comparator<IResource>() {

                public int compare(final IResource o1, final IResource o2) {
                    if (o1.equals(o2)) {
                        return 0;
                    } else if (o1.getFullPath().isPrefixOf(o2.getFullPath())) {
                        return 1;
                    } else {
                        return -1;
                    }
                }

            });
            for (final IResource rsrc : removed) {
                remove(rsrc);
            }
        }
    }

    public void shutdown() {
    }

    public IErlParser getParser() {
        return parser;
    }

    static IErlModule getModuleFromCacheByNameOrPath(final ErlProject project,
            final String moduleName, final String modulePath,
            final IErlModel.Scope scope) throws ErlModelException {
        final ErlModelCache erlModelCache = getErlModelCache();
        if (modulePath != null) {
            final IErlModule module = erlModelCache.getModuleByPath(modulePath);
            if (module != null
                    && (project == null || project.moduleInProject(module))) {
                return module;
            }
        }
        return null;
    }

    static Collection<IErlModule> getAllIncludes(final IErlProject project,
            final boolean checkExternals, final IErlModel.Scope scope)
            throws ErlModelException {
        final List<IErlProject> projects = Lists.newArrayList();
        final List<IErlModule> result = Lists.newArrayList();
        final Set<String> paths = Sets.newHashSet();
        if (project != null) {
            projects.add(project);
            if (scope == IErlModel.Scope.REFERENCED_PROJECTS) {
                projects.addAll(project.getReferencedProjects());
            }
        }
        if (scope == IErlModel.Scope.ALL_PROJECTS) {
            final IErlModel model = CoreScope.getModel();
            for (final IErlProject project2 : model.getErlangProjects()) {
                if (!projects.contains(project2)) {
                    projects.add(project2);
                }
            }
        }
        for (final IErlProject project2 : projects) {
            getAllModulesAux(project2.getIncludes(), result, paths);
        }
        if (checkExternals) {
            getAllModulesAux(project.getExternalIncludes(), result, paths);
        }
        return result;
    }

    static void getAllModulesAux(final Collection<IErlModule> modules,
            final List<IErlModule> result, final Set<String> paths) {
        for (final IErlModule module : modules) {
            final String path = module.getFilePath();
            if (path != null) {
                if (paths.contains(path)) {
                    continue;
                }
                paths.add(path);
            }
            result.add(module);
        }
    }

    static Collection<IErlModule> getAllModules(final IErlProject project,
            final boolean checkExternals, final IErlModel.Scope scope)
            throws ErlModelException {
        final Set<IErlProject> projects = Sets.newHashSet();
        final List<IErlModule> result = Lists.newArrayList();
        final Set<String> paths = Sets.newHashSet();

        if (project != null) {
            projects.add(project);
            if (scope == IErlModel.Scope.REFERENCED_PROJECTS) {
                projects.addAll(project.getReferencedProjects());
            }
        }

        if (scope == IErlModel.Scope.ALL_PROJECTS) {
            final IErlModel model = CoreScope.getModel();
            projects.addAll(model.getErlangProjects());
        }

        for (final IErlProject project2 : projects) {
            ErlModel.getAllModulesAux(project2.getModules(), result, paths);
        }
        if (checkExternals) {
            if (project != null) {
                ErlModel.getAllModulesAux(project.getExternalModules(), result,
                        paths);
            }
            if (scope == IErlModel.Scope.ALL_PROJECTS) {
                for (final IErlProject project2 : projects) {
                    ErlModel.getAllModulesAux(project2.getExternalModules(),
                            result, paths);
                }
            }
        }
        return result;
    }

    static IErlModule findIncludeFromProject(final IErlProject project,
            final String includeName, final String includePath,
            final boolean ignoreCase, final boolean checkExternals,
            final IErlModel.Scope scope) throws ErlModelException {
        if (project != null) {
            final IErlModule module = getModuleFromCacheByNameOrPath(
                    (ErlProject) project, includeName, includePath, scope);
            if (module != null && module.isOnIncludePath()) {
                return module;
            }
        }
        final Collection<IErlModule> includes = getAllIncludes(project,
                checkExternals, scope);
        ErlModelCache.getDefault().putModules(includes);
        if (includePath != null) {
            for (final IErlModule module2 : includes) {
                final String path2 = module2.getFilePath();
                if (path2 != null && includePath.equals(path2)) {
                    return module2;
                }
            }
        }
        if (includeName != null) {
            final boolean hasExtension = CommonUtils.hasExtension(includeName);
            for (final IErlModule module2 : includes) {
                final String name = hasExtension ? module2.getName() : module2
                        .getModuleName();
                if (ignoreCase) {
                    if (includeName.equals(name)) {
                        return module2;
                    }
                } else {
                    if (includeName.equalsIgnoreCase(name)) {
                        return module2;
                    }
                }
            }
        }
        return null;
    }

    public IErlModule findModuleFromProject(final IErlProject project,
            final String moduleName, final String modulePath,
            final IErlModel.Scope scope) throws ErlModelException {
        return findModuleFromProject(project, moduleName, modulePath, false,
                true, scope);
    }

    public IErlModule findIncludeFromProject(final IErlProject project,
            final String moduleName, final String modulePath,
            final IErlModel.Scope scope) throws ErlModelException {
        return findIncludeFromProject(project, moduleName, modulePath, false,
                true, scope);
    }

    static IErlModule findModuleFromProject(final IErlProject project,
            final String moduleName, final String modulePath,
            final boolean ignoreCase, final boolean checkExternals,
            final IErlModel.Scope scope) throws ErlModelException {
        if (project != null) {
            final IErlModule module = getModuleFromCacheByNameOrPath(
                    (ErlProject) project, moduleName, modulePath, scope);
            if (module != null && module.isOnSourcePath()) {
                return module;
            }
        }
        final Collection<IErlModule> modules = getAllModules(project,
                checkExternals, scope);
        ErlModelCache.getDefault().putModules(modules);
        if (modulePath != null) {
            for (final IErlModule module2 : modules) {
                final String path2 = module2.getFilePath();
                if (path2 != null && modulePath.equals(path2)) {
                    return module2;
                }
            }
        }
        if (moduleName != null) {
            final boolean hasExtension = CommonUtils.hasExtension(moduleName);
            for (final IErlModule module2 : modules) {
                final String name = hasExtension ? module2.getName() : module2
                        .getModuleName();
                if (ignoreCase) {
                    if (moduleName.equalsIgnoreCase(name)) {
                        return module2;
                    }
                } else {
                    if (moduleName.equals(name)) {
                        return module2;
                    }
                }
            }
        }
        return null;
    }

    public IErlModule findIncludeFromModule(final IErlModule module,
            final String includeName, final String includePath,
            final IErlModel.Scope scope) throws ErlModelException {
        final IParent parent = module.getParent();
        if (parent instanceof IErlFolder) {
            final IErlFolder folder = (IErlFolder) parent;
            folder.open(null);
            final IErlModule include = folder.findInclude(includeName,
                    includePath);
            if (include != null) {
                return include;
            }
        }
        return findIncludeFromProject(module.getProject(), includeName,
                includePath, false, true, scope);
    }

}
