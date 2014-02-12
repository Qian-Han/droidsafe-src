package droidsafe.transforms.objsensclone;

import droidsafe.analyses.pta.PTABridge;
import droidsafe.analyses.value.VAResultContainerClassGenerator;
import droidsafe.android.app.Hierarchy;
import droidsafe.android.app.Project;
import droidsafe.android.system.API;
import droidsafe.main.Config;
import droidsafe.transforms.TransformsUtils;
import droidsafe.utils.SootUtils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import soot.Body;
import soot.jimple.AnyNewExpr;
import soot.jimple.AssignStmt;
import soot.jimple.ClassConstant;
import soot.jimple.InvokeExpr;
import soot.jimple.Jimple;
import soot.jimple.JimpleBody;
import soot.jimple.NewExpr;
import soot.jimple.NullConstant;
import soot.jimple.StaticInvokeExpr;
import soot.jimple.StringConstant;
import soot.jimple.internal.JAssignStmt;
import soot.jimple.internal.JGotoStmt;
import soot.jimple.internal.JIfStmt;
import soot.jimple.SpecialInvokeExpr;
import soot.jimple.Stmt;
import soot.jimple.StmtBody;
import soot.Local;
import soot.PrimType;
import soot.RefLikeType;
import soot.RefType;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.SootMethodRef;
import soot.Type;
import soot.Value;
import soot.VoidType;
import soot.util.Chain;
import soot.ValueBox;

/**
 * Introduce selective object sensitivity by cloning certain api classes.  
 * When a new expression of one of the tracked classes is encountered in application code, 
 * a clone is created of the original class (clone contains methods from ancestors), and the
 * new expression (plus constructor call) are changed to reference the new cloned class.  
 * 
 * Does not rely on PTA.
 * 
 * @author mgordon
 *
 */
public class ObjectSensitivityCloner {

    /** logger object */
    private static final Logger logger = LoggerFactory.getLogger(ObjectSensitivityCloner.class);

    /** Do not clone classes that will add more than this percentage to the total number of classes */
    private static final double CLONING_THRESHOLD = .15;

    private static final boolean CLONE_STRINGS = false;

    /** list of classes resolved by VA, some of which should be cloned */
    public static final Set<SootClass> VA_RESOLVED_CLASSES = 
            VAResultContainerClassGenerator.getClassesAndFieldsToModel(false).keySet();

    private static final Set<String> STRING_CLASSES = 
            new HashSet<String>(Arrays.asList("java.lang.String", 
                "java.lang.StringBuffer",
                    "java.lang.StringBuilder"));

    private int numClonedClasses = 0;
    private int cloneErrors = 0;
    /** reachable assignment statements that have an allocation, that we search when cloning */
    private HashMap<SootMethod, List<AssignStmt>> allocStmts;
    private static ObjectSensitivityCloner v;

    private Map<SootClass, Set<SootClass>> originalToCloneMap;

    private Map<SootClass, SootClass> cloneToOriginalMap;

    private Map<SootMethod,Set<SootMethod>> methodToClonesMap;

    private Set<SootMethod> clonedMethodsAdded;

    public boolean hasRun = false;

    public static ObjectSensitivityCloner v() {
        if (v == null)
            v = new ObjectSensitivityCloner();

        return v;
    }

    public static void reset() {
        v = null;
    }


    private ObjectSensitivityCloner() {
        cloneToOriginalMap = new HashMap<SootClass, SootClass>();
        originalToCloneMap = new HashMap<SootClass, Set<SootClass>>();
        clonedMethodsAdded = new HashSet<SootMethod>();
        methodToClonesMap = new HashMap<SootMethod,Set<SootMethod>>();
        allocStmts = new HashMap<SootMethod, List<AssignStmt>>();
    }

    private void addToAllocList(Collection<SootMethod> methods) {
        for (SootMethod method : methods) {
            if (method.isAbstract() || !method.isConcrete())
                continue;

            Body body = method.getActiveBody();
            StmtBody stmtBody = (StmtBody)body;
            Chain units = stmtBody.getUnits();
            Iterator stmtIt = units.snapshotIterator();

            while (stmtIt.hasNext()) {
                Stmt stmt = (Stmt)stmtIt.next();

                if (stmt instanceof AssignStmt) {
                    AssignStmt assign = (AssignStmt) stmt;
                    if (assign.getRightOp() instanceof NewExpr && assign.getLeftOp() instanceof Local) {
                        if (!allocStmts.containsKey(method)) {
                            allocStmts.put(method, new LinkedList<AssignStmt>());
                        }
                        allocStmts.get(method).add(assign);
                    }
                }
            }
        }
    }

    /**
     * Given a cloned class, get the original class.
     */
    public SootClass getOriginalClass(SootClass clz) {
        if (!isClonedClass(clz)) {
            logger.error("Cannot call on non-cloned class!: {}", clz);
            droidsafe.main.Main.exit(1);
        }

        return cloneToOriginalMap.get(clz);
    }

    /**
     * Get all the cloned classes that represent the given original class.
     */
    public Set<SootClass> getClonedClasses(SootClass clz) {
        if (isClonedClass(clz)) {
            logger.error("Cannot call on cloned class!: {}", clz);
            droidsafe.main.Main.exit(1);
        }

        if (!originalToCloneMap.containsKey(clz))
            return Collections.emptySet();

        return originalToCloneMap.get(clz);

    }

    public boolean isClonedClass(SootClass clz) {
        return cloneToOriginalMap.keySet().contains(clz);
    }

    public boolean isClonedMethod(SootMethod method) {
        return clonedMethodsAdded.contains(method);
    }

    public Set<SootMethod> getMethodsThatWereCloned() {
        return methodToClonesMap.keySet();
    }

    public Set<SootMethod> getAllClonedMethods(){
        return clonedMethodsAdded;
    }

    public Set<SootMethod> getClonedContextMethods(SootMethod method) {
        SootClass clz = method.getDeclaringClass();

        if (isClonedClass(clz)) {
            logger.error("Trying to get cloned method context for cloned method: {}", method);
            droidsafe.main.Main.exit(1);
        }

        if (methodToClonesMap.containsKey(method))
            return methodToClonesMap.get(method);
        else 
            return Collections.emptySet();
    }

    /**
     * Run the cloner on all new expression of classes in the list of classes to clone.  Produce clones for each
     * new expression.
     */
    public void runForVA() {
        hasRun = true;
        numClonedClasses = 0;
        AllocationGraph aGraph = new AllocationGraph();

        //build list of allocations that we are going to search for each clone
        addToAllocList(PTABridge.v().getAllReachableMethods());

        FileWriter fw = null;

        try {
            fw = new FileWriter(Project.v().getOutputDir() + File.separator + 
                    "obj-sens-cloner-va-stats.csv");

            fw.write(aGraph.getGraphStats());

            List<SootClass> workList = aGraph.workList();
            fw.write("Total classes to consider: " + workList.size() + "\n");
            fw.write("Class,InDegree,OutDegree,TotalClasses,PercentChange\n");

            int prevClassCount = Scene.v().getClasses().size();
            for (SootClass currentClass : workList) {
                //don't clone strings on first run

                fw.write("Considering " + currentClass + "\n");
                
                if (ClassCloner.isClonedClass(currentClass)) {
                    fw.write("Not cloning because already a clone (probably made by fallback modeling)\n");
                    continue;
                }

                if (!CLONE_STRINGS && STRING_CLASSES.contains(currentClass.getName())) {
                    fw.write("\tDo not touch: String class\n");
                    continue;
                }


                boolean cloned = false;

                //the percent change in additional classes if we clone all the allocations of currently class
                double percentChange = ((double)aGraph.getInDegree(currentClass)) / ((double)prevClassCount);

                //ONLY CLONE IF THERE IS AN INDEGREE OF > 1 AND WE WILL NOT HIT THE CLONE THRESHOLD!                
                if (aGraph.getInDegree(currentClass) > 1 && 
                        percentChange < CLONING_THRESHOLD) {
                    cloned = cloneAllAllocsOfClass(currentClass, aGraph);
                }

                if (cloned) {
                    int currentClassCount = Scene.v().getClasses().size(); 
                    fw.write(String.format("\tCloned: %s,%d,%d,%d,%f\n", 
                        currentClass, 
                        aGraph.getInDegree(currentClass),
                        aGraph.getOutDegree(currentClass),
                        currentClassCount,
                        ((currentClassCount - prevClassCount) / (float)prevClassCount) * 100.0f
                            ));

                    prevClassCount = currentClassCount;

                    //remove original class from allocation graph
                    aGraph.removeClass(currentClass);

                    //remove methods from the original class as being reachable, and thus not going to force a
                    //clone
                    for (SootMethod method : currentClass.getMethods()) { 
                        if (!method.isStatic()) {
                            allocStmts.remove(method);
                        }
                    }
                } 

                if (!cloned) {
                    fw.write("\tNot cloned: but removed inheritance\n");
                    //if not cloning, then just clone inherited methods of class
                    CloneInheritedMethods cim = new CloneInheritedMethods(currentClass, false);
                    cim.transform();
                    rememberCloneContext(cim.getCloneToOriginalMap());
                    //update allocation graph for new methods
                    for (SootMethod method : cim.getReachableClonedMethods()) {
                        aGraph.updateAllocationGraph(method);
                        List<SootMethod> tempSet = new ArrayList<SootMethod>(1);
                        tempSet.add(method);
                        addToAllocList(tempSet);
                    }

                }
                fw.flush();
            }

            fw.close();
        } catch (IOException e) {
            logger.error("Error writing stats file.");
            droidsafe.main.Main.exit(1);
        } 

        Scene.v().releaseActiveHierarchy();
        Scene.v().releaseFastHierarchy();

        System.out.printf("Finished cloning: added %d classes (%d errors).\n", numClonedClasses, cloneErrors);
    }

    /*
    public void runForInfoFlow() {
        numClonedClasses = 0;

        initMasterList();

        for (String stringClass : STRING_CLASSES) {
            SootClass currentClass = Scene.v().getSootClass(stringClass);
            cloneAllAllocsOfClass(currentClass);
        }

        Scene.v().releaseActiveHierarchy();
        Scene.v().releaseFastHierarchy();

        System.out.printf("Finished cloning: added %d classes (%d errors).\n", numClonedClasses, cloneErrors);
    }
     */

    /**
     * Add the effects of cloning to the remember clone context for each original method
     * @param cloneToOrg
     */
    private void rememberCloneContext(Map<SootMethod,SootMethod> cloneToOrg) {
        for (SootMethod clone : cloneToOrg.keySet()) {
            SootMethod org = cloneToOrg.get(clone);

            clonedMethodsAdded.add(clone);

            if (!methodToClonesMap.containsKey(org))
                methodToClonesMap.put(org, new LinkedHashSet<SootMethod>());

            methodToClonesMap.get(org).add(clone);
        }
    }

    /**
     * return true if we have cloned
     * @param currentClass
     * @return
     */
    private boolean cloneAllAllocsOfClass(SootClass currentClass, AllocationGraph aGraph) {
        if (originalToCloneMap.keySet().contains(currentClass) || isClonedClass(currentClass)) {
            logger.error("Trying to clone allocs for already cloned class: {}", currentClass);
            droidsafe.main.Main.exit(1);
        }

        boolean haveCloned = false;

        //System.out.println("Cloning " + currentClass.getName());

        //create a list to iterate over that is the current snap shot of the master list
        //because we update the master list for each clone...
        //List<SootMethod> iterationList = new LinkedList<SootMethod>();
        //iterationList.addAll(masterMethodList);

        HashMap<SootMethod,List<AssignStmt>> allocs = (HashMap<SootMethod,List<AssignStmt>>)allocStmts.clone();

        for (Map.Entry<SootMethod, List<AssignStmt>> entry : allocs.entrySet()) {
            SootMethod method = entry.getKey();
            List<AssignStmt> assigns = entry.getValue();

            for (AssignStmt assign : assigns) {
                NewExpr newExpr = (NewExpr) assign.getRightOp();
                SootClass base = newExpr.getBaseType().getSootClass();
                String baseClassName = base.getName();

                if (!currentClass.equals(base)) 
                    continue;

                logger.info("Found new expr to replace and clone class: {} {}\n",
                    method, assign);

                //now change the constructor call after find the appropriate call to change
                try {
                    Set<Local> local = new HashSet<Local>();
                    local.add((Local)assign.getLeftOp());

                    Body body = method.getActiveBody();
                    StmtBody stmtBody = (StmtBody)body;
                    Chain units = stmtBody.getUnits();

                    Stmt specialStmt = TransformsUtils.findConstructorCall(method,
                        (Stmt)units.getSuccOf(assign), local);

                    
                    if (specialStmt.containsInvokeExpr() && specialStmt.getInvokeExpr() instanceof SpecialInvokeExpr) {
                        SpecialInvokeExpr special = (SpecialInvokeExpr)specialStmt.getInvokeExpr();
                        //found an appropriate constructor call
                        //clone class and install it as an new API class

                        ClassCloner cCloner = ClassCloner.cloneClass(base, false);

                        SootClass cloned = cCloner.getClonedClass();

                        //add all cloned methods clone to the master list
                        addToAllocList(cCloner.getReachableClonedMethods());

                        rememberCloneContext(cCloner.getCloneToOriginalMap());

                        //update allocation graph for new methods
                        for (SootMethod newMethod : cCloner.getReachableClonedMethods())
                            aGraph.updateAllocationGraph(newMethod);

                        SootMethodRef origMethodRef = special.getMethodRef();

                        //replace old constructor call with call to cloned class
                        special.setMethodRef(Scene.v().makeMethodRef(cloned, 
                            origMethodRef.name(), 
                            origMethodRef.parameterTypes(), 
                            origMethodRef.returnType(), 
                            origMethodRef.isStatic()));

                        //replace new expression with new expression of cloned class
                        newExpr.setBaseType(RefType.v(cloned));

                        numClonedClasses++;
                        cloneToOriginalMap.put(cloned, currentClass);

                        if (!originalToCloneMap.containsKey(currentClass)) 
                            originalToCloneMap.put(currentClass, new HashSet<SootClass>());

                        originalToCloneMap.get(currentClass).add(cloned);


                        haveCloned = true;
                    } else {
                        throw new Exception("Special Invoke Not Found!");
                    }
                } catch (Exception e) {
                    logger.info("Error processing constructor call after modifying new expr: {} in {}", 
                        assign, method, e);
                    //droidsafe.main.Main.exit(1);
                    cloneErrors++;
                }
            }
        }
        return haveCloned;
    }

   
    /**
     * Given a non-cloned method and a local of the method, for all cloned context methods, find the locals
     * that correspond.
     */
    public Set<Local> getClonedLocals(SootMethod origMeth, Local local) {
        Set<Local> clonedLocals = new HashSet<Local>();
        for (SootMethod clone : getClonedContextMethods(origMeth)) {
            Body body = clone.retrieveActiveBody();

            for (Local cloneLocal : body.getLocals()) {
                if (cloneLocal.getName().equals(local.getName())) {
                    clonedLocals.add(cloneLocal);
                    break;
                }
            }
        }
        return clonedLocals;
    }


    public Local getClonedLocal(SootMethod origMeth, Local local, SootMethod clone) {
        if (!getClonedContextMethods(origMeth).contains(clone)) {
            logger.error("Not a context clone: {} {}", origMeth, clone);
            droidsafe.main.Main.exit(1);
        }

        Body body = clone.retrieveActiveBody();

        for (Local cloneLocal : body.getLocals()) {
            if (cloneLocal.getName().equals(local.getName())) {
                return cloneLocal;
            }
        }

        return null;
    }
}

class ToStringComparator implements Comparator<Object> {
    @Override
    public int compare(Object o1, Object o2) {
        return o1.toString().compareTo(o2.toString());
    }

}