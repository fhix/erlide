package org.erlide.cover.ui.actions;

import java.io.File;
import java.util.Collection;

import org.eclipse.jface.viewers.TreeViewer;
import org.erlide.core.CoreScope;
import org.erlide.cover.core.Activator;
import org.erlide.cover.core.Logger;
import org.erlide.cover.core.MD5Checksum;
import org.erlide.cover.views.model.FunctionStats;
import org.erlide.cover.views.model.ICoverageObject;
import org.erlide.cover.views.model.ModuleStats;
import org.erlide.cover.views.model.StatsTreeModel;
import org.erlide.cover.views.model.StatsTreeObject;

/**
 * Showing annotations from context menu for specified objects
 * 
 * @author Aleksandra Lipiec <aleksandra.lipiec@erlang-solutions.com>
 * 
 */
public class ShowCoverageAction extends CoverageAction {

    private Logger log;

    public ShowCoverageAction(TreeViewer viewer) {
        super(viewer);
        log = Activator.getDefault();
    }

    @Override
    protected void perform(StatsTreeObject selection) {

        if (selection instanceof ModuleStats) {
            ModuleStats module = (ModuleStats) selection;
            String name = module.getLabel() + ".erl";
            if (ifMarkAnnotations(module)) {
                module.couldBeMarked = true;
                marker.addAnnotationsToFile(name);
            }
        } else if (selection instanceof FunctionStats) {
            FunctionStats fs = (FunctionStats) selection;
            ModuleStats module = (ModuleStats) fs.getParent();
            String name = module.getLabel() + ".erl";

            if (ifMarkAnnotations(module)) {
                log.info(fs.getLineStart());
                log.info(fs.getLineEnd());
                module.couldBeMarked = true;
                marker.addAnnotationsFragment(name, fs.getLineStart(),
                        fs.getLineEnd());
            }

        } else if( selection.equals(StatsTreeModel.getInstance().getRoot())){
            //TODO: check annotation tree, only if root mark all annotations
            Collection<ICoverageObject> col = selection.getModules();
            for(ICoverageObject module : col) {
                if(ifMarkAnnotations((ModuleStats)module)) {
                    ((ModuleStats)module).couldBeMarked = true;
                } else {
                    ((ModuleStats)module).couldBeMarked = false;
                }
            }
            marker.addAnnotations();
        } else {
            Collection<ICoverageObject> col = selection.getModules();
            for(ICoverageObject module : col) {
                if(ifMarkAnnotations((ModuleStats) module)) {
                    String name = module.getLabel() + ".erl";
                    ((ModuleStats)module).couldBeMarked = true;
                    marker.addAnnotationsToFile(name);
                }
            }
        }

    }

    // calculate md5
    private boolean ifMarkAnnotations(ModuleStats module) {
        try {
            File file = new File(CoreScope.getModel()
                    .findModule(module.getLabel()).getFilePath());

            if (module.getMd5().equals(MD5Checksum.getMD5(file)))
                return true;
        } catch (Exception e) {
            // TODO
            e.printStackTrace();
        }
        return false;
    }

}
