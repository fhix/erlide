package org.erlide.ui.navigator;

import java.util.Collection;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.ui.navigator.SaveablesProvider;
import org.eclipse.ui.progress.UIJob;
import org.erlide.core.CoreScope;
import org.erlide.core.model.erlang.IErlModule;
import org.erlide.core.model.root.ErlModelException;
import org.erlide.core.model.root.IErlElement;
import org.erlide.core.model.root.IErlModel;
import org.erlide.core.model.root.IErlModelChangeListener;
import org.erlide.core.model.root.IErlProject;
import org.erlide.core.model.root.IOpenable;
import org.erlide.core.model.root.IParent;
import org.erlide.jinterface.ErlLogger;

public class ErlangFileContentProvider implements ITreeContentProvider,
        IResourceChangeListener, IResourceDeltaVisitor,
        IErlModelChangeListener, IAdaptable {

    private static final Object[] NO_CHILDREN = new Object[0];

    StructuredViewer viewer;

    /**
     * Create the PropertiesContentProvider instance.
     * 
     * Adds the content provider as a resource change listener to track changes
     * on disk.
     * 
     */
    public ErlangFileContentProvider() {
        ResourcesPlugin.getWorkspace().addResourceChangeListener(this,
                IResourceChangeEvent.POST_CHANGE);
        final IErlModel mdl = CoreScope.getModel();
        mdl.addModelChangeListener(this);
    }

    /**
     * Return the model elements for a *.erl IFile or NO_CHILDREN for otherwise.
     */
    public Object[] getChildren(Object parentElement) {
        try {
            if (parentElement instanceof IFile) {
                parentElement = CoreScope.getModel().findModule(
                        (IFile) parentElement);
            }
            if (parentElement instanceof IOpenable) {
                final IOpenable openable = (IOpenable) parentElement;
                openable.open(null);
            }
            if (parentElement instanceof IParent) {
                final IParent parent = (IParent) parentElement;
                final Collection<IErlElement> children = parent.getChildren();
                return children.toArray();
            }
        } catch (final ErlModelException e) {
            ErlLogger.warn(e);
        }
        return NO_CHILDREN;
    }

    /**
     * Load the model from the given file, if possible.
     * 
     * @param modelFile
     *            The IFile which contains the persisted model
     */

    public Object getParent(final Object element) {
        if (element instanceof IErlElement) {
            final IErlElement elt = (IErlElement) element;
            final IParent parent = elt.getParent();
            if (parent instanceof IErlModule || parent instanceof IErlProject) {
                final IErlElement e = (IErlElement) parent;
                return e.getCorrespondingResource();
            }
        }
        return null;
    }

    public boolean hasChildren(final Object element) {
        if (element instanceof IFile || element instanceof IErlModule) {
            // it was too slow to open all modules to find out;
            // empty modules aren't widely used anyway :-)
            return true;
        } else if (element instanceof IParent) {
            final IParent parent = (IParent) element;
            return parent.hasChildren();
        }
        return false;
    }

    public Object[] getElements(final Object inputElement) {
        return getChildren(inputElement);
    }

    public void dispose() {
        ResourcesPlugin.getWorkspace().removeResourceChangeListener(this);
        CoreScope.getModel().removeModelChangeListener(this);
    }

    public void inputChanged(final Viewer theViewer, final Object oldInput,
            final Object newInput) {
        if (theViewer instanceof StructuredViewer) {
            viewer = (StructuredViewer) theViewer;
        }
    }

    public void resourceChanged(final IResourceChangeEvent event) {
        final IResourceDelta delta = event.getDelta();
        try {
            if (delta != null) {
                // ErlLogger.debug("change " + event.toString());
                delta.accept(this);
            }
        } catch (final CoreException e) {
            ErlLogger.warn(e);
        }
    }

    public boolean visit(final IResourceDelta delta) {

        final IResource source = delta.getResource();
        switch (source.getType()) {
        case IResource.ROOT:
        case IResource.PROJECT:
        case IResource.FOLDER:
            return true;
        case IResource.FILE:
            final IFile file = (IFile) source;
            // if (ErlideUtil.isErlangFileContentFileName(file.getName())) {
            doRefresh(file);
            // }
            return false;
        }
        return false;
    }

    private void doRefresh(final IFile file) {
        final String title = "Update Erlang Model in CommonViewer: "
                + file.getName();
        new UIJob(title) {
            @Override
            public IStatus runInUIThread(final IProgressMonitor monitor) {
                if (viewer != null && !viewer.getControl().isDisposed()) {
                    viewer.refresh(file);
                }
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    public void elementChanged(final IErlElement element) {
        if (element instanceof IErlModule) {
            final IErlModule m = (IErlModule) element;
            final IResource r = m.getResource();
            if (r instanceof IFile) {
                doRefresh((IFile) r);
            }
        }
    }

    public Object getAdapter(@SuppressWarnings("rawtypes") final Class required) {
        if (SaveablesProvider.class.equals(required)) {
            // TODO return something useful
            return null;
        }
        return null;
    }
}
