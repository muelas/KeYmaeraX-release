package edu.cmu.cs.ls.keymaerax.tacticsinterface

import edu.cmu.cs.ls.keymaerax.bellerophon.{BelleProvable, BelleValue, BelleExpr, IOListener}
import edu.cmu.cs.ls.keymaerax.core.Provable
import edu.cmu.cs.ls.keymaerax.hydra.{ExecutionStepPOJO, DBAbstraction, ExecutionStepStatus}
import edu.cmu.cs.ls.keymaerax.hydra.ExecutionStepStatus.ExecutionStepStatus

/**
  * Created by bbohrer on 11/20/15.
  */
object TacticDebugger {

  class DebuggerListener (db: DBAbstraction, executionId: Int, executableId: Int, userExecuted: Boolean,
                          alternativeOrder: Int, branch:Either[Int, String]) extends IOListener {
    class TraceNode (isFirstNode: Boolean){
      var id: Option[Int] = None
      var parent: TraceNode = null
      var sibling: TraceNode = null
      var input: Provable = null
      var output: Provable = null
      var status: ExecutionStepStatus = null
      var reverseChildren: List[TraceNode] = Nil
      def children = reverseChildren.reverse
      /* This is generated by the DB, so it will not be present when we first create an object for the step. However,
         we need to set it once it has been generated so other steps can get the appropriate ID.
       */
      var stepId: Option[Int] = None
      val altOrder = if (isFirstNode) alternativeOrder else 0
      val branchLabel: String = branch match {case Right(label) => label case _ => null}
      val branchOrder: Option[Int] = branch match {case Left(order) => Some(order) case _ => None}
      val userExe = if(userExecuted) isFirstNode else false

      var inputProvableId: Option[Int] = None
      var outputProvableId: Option[Int] = None

      def getInputProvableId:Int = {
        if (input != null && inputProvableId.isEmpty)
          inputProvableId = Some(db.serializeProvable(input))
        inputProvableId.get
      }

      def getOutputProvableId:Option[Int] = {
        if (output != null && outputProvableId.isEmpty)
          outputProvableId = Some(db.serializeProvable(output))
        outputProvableId
      }

      def asPOJO: ExecutionStepPOJO = {
        val siblingStep = if (sibling == null) None else sibling.stepId
        val parentStep = if (parent == null) None else parent.stepId
        new ExecutionStepPOJO (stepId, executionId, siblingStep, parentStep, branchOrder,
          Option(branchLabel), alternativeOrder,status, executableId, getInputProvableId, getOutputProvableId,
          userExecuted)
      }
    }

    var youngestSibling: TraceNode = null
    var node: TraceNode = null
    var isDead: Boolean = false

    def begin(v: BelleValue, expr: BelleExpr): Unit = {
      synchronized {
        if(isDead) return
        val parent = node
        node = new TraceNode(isFirstNode = parent == null)
        node.parent = parent
        node.sibling = youngestSibling
        node.input = v match {
          case BelleProvable(p) => p
        }
        node.status = ExecutionStepStatus.Running

        if (parent != null) {
          parent.status = ExecutionStepStatus.DependsOnChildren
          parent.reverseChildren = node :: parent.reverseChildren
          db.updateExecutionStatus(parent.stepId.get, parent.status)
        }
        node.stepId = Some(db.addExecutionStep(node.asPOJO))
      }
    }

    def end(v: BelleValue, expr: BelleExpr, result: BelleValue): Unit = {
      synchronized {
        if(isDead) return
        val current = node
        node = node.parent
        youngestSibling = current
        current.output = result match {
          case BelleProvable(p) => p
        }
        current.status = ExecutionStepStatus.Finished
        db.updateExecutionStatus(current.stepId.get, current.status)
      }
    }

    /** Called by HyDRA before killing the interpreter's thread. Updates the database to reflect that the computation
      * was interrupted. There are two race conditions to worry about here:
      * (1) kill() can race with a call to begin/end that was in progress when kill() started. This is resolved with
      * a mutex (synchronized{} blocks)
      * (2) An in-progress computation can race with a kill signal (sent externally after kill() is called). This is
      * resolved by setting a flag during kill() which turns future operations into a no-op. */
    def kill(): Unit = {
      synchronized {
        isDead = true
        db.updateExecutionStatus(node.stepId.get, ExecutionStepStatus.Aborted)
      }
    }
  }
}
