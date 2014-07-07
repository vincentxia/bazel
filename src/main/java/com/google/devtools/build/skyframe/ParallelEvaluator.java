// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.skyframe;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.NestedSetVisitor;
import com.google.devtools.build.lib.concurrent.AbstractQueueVisitor;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;
import com.google.devtools.build.lib.events.DelegatingOnlyErrorsEventListener;
import com.google.devtools.build.lib.events.ErrorEventListener;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.events.StoredErrorEventListener;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.util.GroupedList.GroupedListHelper;
import com.google.devtools.build.skyframe.BuildingState.DirtyState;
import com.google.devtools.build.skyframe.NodeEntry.DependencyState;
import com.google.devtools.build.skyframe.NodeProgressReceiver.EvaluationState;
import com.google.devtools.build.skyframe.Scheduler.SchedulerException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

/**
 * Updates a graph given a set of root nodes by doing a recursive traversal over the dependency
 * structure and creating all intermediate nodes. Cycles are not allowed and are detected during
 * the traversal.
 *
 * <p>This class implements multi-threaded evaluation. This is a fairly complex process that has
 * strong consistency requirements between the {@link ProcessableGraph}, the nodes in the graph of
 * type {@link NodeEntry}, the work queue, and the set of inflight nodes.
 *
 * <p>The basic invariants are:
 *
 * <p>A node can be in one of three states: ready, waiting, and done. A node is ready if and only
 * if all of its dependencies have been signaled. A node is done if it has a value. It is waiting
 * if not all of its dependencies have been signaled.
 *
 * <p>A node must be in the work queue if and only if it is ready. It is an error for a node to be
 * in the work queue twice at the same time.
 *
 * <p>A node is considered inflight if it has been created, and is not done yet. In case of an
 * interrupt, the work queue is discarded, and the inflight set is used to remove partially
 * computed nodes.
 *
 * <p>Each evaluation of the graph takes place at a "version," which is currently given by a
 * non-negative {@code long}. The version can also be thought of as an "mtime." Each node in the
 * graph has a version, which is the last version at which its value changed. This version data is
 * used to avoid unnecessary re-evaluation of nodes. If a node is re-evaluated and found to have the
 * same value as before, its version (mtime) remains the same. If all of a node's children's have
 * the same version as before, its re-evaluation can be skipped.
 */
final class ParallelEvaluator implements Evaluator {
  private final ProcessableGraph graph;
  private final long graphVersion;

  private final Predicate<NodeKey> nodeEntryIsDone = new Predicate<NodeKey>() {
    @Override
    public boolean apply(NodeKey nodeKey) {
      return isDoneForBuild(graph.get(nodeKey));
    }
  };

  private final ImmutableMap<? extends NodeType, ? extends NodeBuilder> nodeBuilders;

  private final ErrorEventListener reporter;
  private final NestedSetVisitor<TaggedEvents> replayingNestedSetEventVisitor;
  private final boolean keepGoing;
  private final int threadCount;
  @Nullable private final NodeProgressReceiver progressReceiver;

  private static final Interner<NodeKey> KEY_CANONICALIZER =  Interners.newWeakInterner();

  @VisibleForTesting
  ParallelEvaluator(ProcessableGraph graph, long graphVersion,
                    ImmutableMap<? extends NodeType, ? extends NodeBuilder> nodeBuilders,
                    final ErrorEventListener reporter,
                    boolean keepGoing) {
    this(graph, graphVersion, nodeBuilders, reporter, new AutoUpdatingGraph.EmittedEventState(),
        keepGoing, AutoUpdatingGraph.DEFAULT_THREAD_COUNT, null);
  }

  ParallelEvaluator(ProcessableGraph graph, long graphVersion,
                    ImmutableMap<? extends NodeType, ? extends NodeBuilder> nodeBuilders,
                    final ErrorEventListener reporter,
                    AutoUpdatingGraph.EmittedEventState emittedEventState,
                    boolean keepGoing, int threadCount,
                    @Nullable NodeProgressReceiver progressReceiver) {
    this.graph = graph;
    this.nodeBuilders = nodeBuilders;
    Preconditions.checkState(graphVersion >= 0L, graphVersion);
    this.graphVersion = graphVersion;
    this.reporter = Preconditions.checkNotNull(reporter);
    this.keepGoing = keepGoing;
    this.threadCount = threadCount;
    this.progressReceiver = progressReceiver;
    this.replayingNestedSetEventVisitor =
        new NestedSetVisitor<>(new NestedSetEventReceiver(reporter), emittedEventState);
  }

  /**
   * Receives the events from the NestedSet and delegates to the reporter
   * if {@link ErrorEventListener#showOutput(String)} returns true. Otherwise if
   * it is not an error it ignores the event.
   */
  private static class NestedSetEventReceiver implements NestedSetVisitor.Receiver<TaggedEvents> {

    private final ErrorEventListener reporter;
    private final DelegatingOnlyErrorsEventListener onlyErrorsReporter;

    public NestedSetEventReceiver(ErrorEventListener reporter) {
      this.reporter = reporter;
      onlyErrorsReporter = new DelegatingOnlyErrorsEventListener(reporter);
    }

    @Override
    public void accept(TaggedEvents event) {
      String tag = event.getTag();
      if (tag == null || reporter.showOutput(tag)) {
        Event.replayEventsOn(reporter, event.getEvents());
      } else {
        Event.replayEventsOn(onlyErrorsReporter, event.getEvents());
      }
    }
  }

  /**
   * A suitable {@link NodeBuilder.Environment} implementation.
   */
  private class NodeBuilderEnvironment implements NodeBuilder.Environment {
    private boolean building = true;
    private boolean depsMissing = false;
    private final NodeKey nodeKey;
    private Node nodeValue = null;
    private ErrorInfo errorInfo = null;
    private final Map<NodeKey, NodeWithMetadata> bubbleErrorInfo;
    /** The set of nodes previously declared as dependencies. */
    private final Set<NodeKey> directDeps;

    /**
     * The grouped list of nodes requested during this build as dependencies. On a subsequent build,
     * if this node is dirty, all deps in the same dependency group can be checked in parallel for
     * changes. In other words, if dep1 and dep2 are in the same group, then dep1 will be checked in
     * parallel with dep2. See {@link #getDeps} for more.
     */
    private final GroupedListHelper<NodeKey> newlyRequestedDeps = new GroupedListHelper<>();

    /**
     * The node visitor managing the thread pool. Used to enqueue parents when this node is
     * finished, and, during testing, to block until an exception is thrown if a node builder
     * requests that.
     */
    private final NodeVisitor visitor;

    /** The set of errors encountered while fetching children. */
    private final Collection<ErrorInfo> childErrorInfos = new LinkedHashSet<>();
    private final StoredErrorEventListener listener = new StoredErrorEventListener() {
      @Override
      public void info(Location location, String message) {
        checkActive();
        throw new UnsupportedOperationException("Nodes should not display INFO messages: " +
            nodeKey + " printed " + location + ": " + message);
      }

      @Override
      public void progress(Location location, String message) {
        checkActive();
        // Progress messages are printed immediately and not stored for replay.
        reporter.progress(location, message);
      }

      @Override
      public void warn(Location location, String message) {
        checkActive();
        super.warn(location, message);
      }

      @Override
      public void error(Location location, String message) {
        checkActive();
        super.error(location, message);
      }
    };

    private NodeBuilderEnvironment(NodeKey nodeKey, Set<NodeKey> directDeps, NodeVisitor visitor) {
      this(nodeKey, directDeps, null, visitor);
    }

    private NodeBuilderEnvironment(NodeKey nodeKey, Set<NodeKey> directDeps,
        @Nullable Map<NodeKey, NodeWithMetadata> bubbleErrorInfo, NodeVisitor visitor) {
      this.nodeKey = nodeKey;
      this.directDeps = ImmutableSet.copyOf(directDeps);
      this.bubbleErrorInfo = bubbleErrorInfo;
      this.childErrorInfos.addAll(childErrorInfos);
      this.visitor = visitor;
    }

    private void checkActive() {
      Preconditions.checkState(building, nodeKey);
    }

    private NestedSet<TaggedEvents> buildEvents(boolean missingChildren) {
      // Aggregate the nested set of events from the direct deps, also adding the events from
      // building this node.
      NestedSetBuilder<TaggedEvents> eventBuilder = NestedSetBuilder.stableOrder();
      ImmutableList<Event> events = listener.getEvents();
      if (!events.isEmpty()) {
        eventBuilder.add(new TaggedEvents(getTagFromKey(), events));
      }
      for (NodeKey dep : graph.get(nodeKey).getTemporaryDirectDeps()) {
        NodeWithMetadata value = getValueMaybeFromError(dep, bubbleErrorInfo);
        if (value != null) {
          eventBuilder.addTransitive(value.getTransitiveEvents());
        } else {
          Preconditions.checkState(missingChildren, "", dep, nodeKey);
        }
      }
      return eventBuilder.build();
    }

    /**
     * If this node has an error, that is, if errorInfo is non-null, do nothing. Otherwise, set
     * errorInfo to the union of the child errors that were recorded earlier by getNodeOrException,
     * if there are any.
     */
    private void finalizeErrorInfo() {
      if (errorInfo == null && !childErrorInfos.isEmpty()) {
        errorInfo = new ErrorInfo(nodeKey, childErrorInfos);
      }
    }

    private void setNodeValue(Node value) {
      Preconditions.checkState(errorInfo == null && bubbleErrorInfo == null,
          "%s %s %s %s", nodeKey, value, errorInfo, bubbleErrorInfo);
      Preconditions.checkState(nodeValue == null, "%s %s %s", nodeKey, nodeValue, value);
      nodeValue = value;
    }

    /**
     * Set this node to be in error. The node's value must not have already been set. However, all
     * dependencies of this node <i>must</i> already have been registered, since this method may
     * register a dependence on the error transience node, which should always be the last dep.
     */
    private void setError(ErrorInfo errorInfo) {
      Preconditions.checkState(nodeValue == null, "%s %s %s", nodeKey, nodeValue, errorInfo);
      Preconditions.checkState(this.errorInfo == null,
          "%s %s %s", nodeKey, this.errorInfo, errorInfo);

      if (errorInfo.isTransient()) {
        DependencyState triState =
            graph.get(ErrorTransienceNode.key()).addReverseDepAndCheckIfDone(nodeKey);
        Preconditions.checkState(triState == DependencyState.DONE,
            "%s %s %s", nodeKey, triState, errorInfo);

        final NodeEntry state = graph.get(nodeKey);
        state.addTemporaryDirectDeps(
            GroupedListHelper.create(ImmutableList.of(ErrorTransienceNode.key())));
        state.signalDep();
      }

      this.errorInfo = Preconditions.checkNotNull(errorInfo, nodeKey);
    }

    /**
     * Get a child of the node being evaluated, for use by the node builder. If the child has an
     * error and the caller requested that an exception be thrown (by passing in any exception
     * class besides DontThrowAnyException), throws the exception associated to that error if it
     * matches that exception class, for handling by the node builder. Otherwise, just returns null
     * for child with an error.
     */
    private <E extends Throwable> NodeOrException<E> getNodeOrException(NodeKey depKey,
        Class<E> exceptionClass) {
      checkActive();
      depKey = KEY_CANONICALIZER.intern(depKey);  // Canonicalize NodeKeys to save memory.
      boolean throwException = exceptionClass != DontThrowAnyException.class;
      NodeWithMetadata value = getValueMaybeFromError(depKey, bubbleErrorInfo);
      if (value == null) {
        // If this entry is not yet done then (optionally) record the missing dependency and return
        // null.
        depsMissing = true;
        if (bubbleErrorInfo != null) {
          // Nodes being built just for their errors don't get to request new children.
          return NodeOrException.ofNull();
        }
        Preconditions.checkState(!directDeps.contains(depKey), "%s %s %s", nodeKey, depKey, value);
        addDep(depKey);
        depsMissing = true;
        return NodeOrException.ofNull();
      }

      if (!directDeps.contains(depKey)) {
        // If this child is done, we will return it, but also record that it was newly requested so
        // that the dependency can be properly registered in the graph.
        addDep(depKey);
      }

      replayingNestedSetEventVisitor.visit(value.getTransitiveEvents());
      ErrorInfo errorInfo = value.getErrorInfo();

      if (errorInfo != null) {
        childErrorInfos.add(errorInfo);
      }

      if (value.getNode() != null && (keepGoing || errorInfo == null)) {
        // The caller is given the value of the node if there was no error building the node, or if
        // this is a keepGoing build (in which case each node should get child values even if there
        // are also errors).
        return NodeOrException.ofNode(value.getNode());
      }

      // There was an error building the node, which we will either report by throwing an exception
      // or insulate the caller from by returning null.
      Preconditions.checkNotNull(errorInfo, "%s %s %s", nodeKey, depKey, value);
      if (throwException) {
        if (bubbleErrorInfo != null) {
          // Set interrupted status, so that builder doesn't try anything fancy after this.
          Thread.currentThread().interrupt();
        }
        if (errorInfo.getException() != null) {
          // Give builder a chance to handle this exception.
          Throwable e = errorInfo.getException();
          if (exceptionClass.isInstance(e)) {
            return NodeOrException.ofException(exceptionClass.cast(e));
          }
          depsMissing = true;
          return NodeOrException.ofNull();
        }
        // In a cycle.
        Preconditions.checkState(!Iterables.isEmpty(errorInfo.getCycleInfo()), "%s %s %s %s %s",
            nodeKey, depKey, errorInfo, exceptionClass, value);
      }
      depsMissing = true;
      return NodeOrException.ofNull();
    }

    @Override
    public <E extends Throwable> Node getDepOrThrow(NodeKey depKey, Class<E> exceptionClass)
        throws E {
      return getNodeOrException(depKey, exceptionClass).get();
    }

    @Override
    public Node getDep(NodeKey depKey) {
      return getNodeFromNOE(getNodeOrException(depKey, DontThrowAnyException.class));
    }

    @Override
    public <E extends Throwable> Map<NodeKey, NodeOrException<E>> getDepsOrThrow(
        Iterable<NodeKey> depKeys, Class<E> exceptionClass) {
      Map<NodeKey, NodeOrException<E>> result = new HashMap<>();
      newlyRequestedDeps.startGroup();
      for (NodeKey key : depKeys) {
        if (result.containsKey(key)) {
          continue;
        }
        result.put(key, getNodeOrException(key, exceptionClass));
      }
      newlyRequestedDeps.endGroup();
      return ImmutableMap.copyOf(result);
    }

    @Override
    public Map<NodeKey, Node> getDeps(Iterable<NodeKey> depKeys) {
      return Maps.transformValues(getDepsOrThrow(depKeys, DontThrowAnyException.class),
          GET_NODE_FROM_NOE);
    }

    private void addDep(NodeKey key) {
      if (!newlyRequestedDeps.contains(key)) {
        // dep may have been requested already this evaluation. If not, add it.
        newlyRequestedDeps.add(key);
      }
    }

    @Override
    public boolean depsMissing() {
      return depsMissing;
    }

    @Override
    public ErrorEventListener getListener() {
      checkActive();
      return listener;
    }

    private void doneBuilding() {
      building = false;
    }

    /**
     * Apply the change to the graph (mostly) atomically and signal all nodes that are waiting for
     * this node to complete. Adding nodes and signaling is not atomic, but may need to be changed
     * for interruptibility.
     *
     * <p>Parents are only enqueued if {@code enqueueParents} holds. Parents should be enqueued
     * unless (1) this node is being built after the main evaluation has aborted, or (2) this node
     * is being built with --nokeep_going, and so we are about to shut down the main evaluation
     * anyway.
     *
     * <p>The node entry is informed if the node's value and error are definitive via the flag
     * {@code completeNode}.
     */
    void commit(boolean enqueueParents) {
      NodeEntry primaryEntry = Preconditions.checkNotNull(graph.get(nodeKey), nodeKey);
      // Construct the definitive error info, if there is one.
      finalizeErrorInfo();

      // We have the following implications:
      // errorInfo == null => nodeValue != null => enqueueParents.
      // All these implications are strict:
      // (1) errorInfo != null && nodeValue != null happens for nodes with recoverable errors.
      // (2) nodeValue == null && enqueueParents happens for nodes that are found to have errors
      // during a --keep_going build.

      NestedSet<TaggedEvents> events = buildEvents(/*missingChildren=*/false);
      if (nodeValue == null) {
        Preconditions.checkNotNull(errorInfo, "%s %s", nodeKey, primaryEntry);
        // We could consider using max(childVersions) here instead of graphVersion. When full
        // versioning is implemented, this would allow evaluation at a version between
        // max(childVersions) and graphVersion to re-use this result.
        Set<NodeKey> reverseDeps = primaryEntry.setValue(
            NodeWithMetadata.error(errorInfo, events), graphVersion);
        signalNodesAndEnqueueIfReady(enqueueParents ? visitor : null, reverseDeps, graphVersion);
      } else {
        // We must be enqueueing parents if we have a value.
        Preconditions.checkState(enqueueParents, "%s %s", nodeKey, primaryEntry);
        Set<NodeKey> reverseDeps;
        long nodeVersion;
        // If this entry is dirty, setValue may not actually change it, if it determines that
        // the data being written now is the same as the data already present in the entry.
        // We could consider using max(childVersions) here instead of graphVersion. When full
        // versioning is implemented, this would allow evaluation at a version between
        // max(childVersions) and graphVersion to re-use this result.
        reverseDeps = primaryEntry.setValue(
            NodeWithMetadata.normal(nodeValue, errorInfo, events), graphVersion);
        // Note that if this update didn't actually change the node entry, this version may not
        // be the graph version.
        nodeVersion = primaryEntry.getVersion();
        if (progressReceiver != null) {
          // Tell the receiver that this node was built. If nodeVersion < graphVersion, it was not
          // actually changed this run -- when it was written above, its version stayed below this
          // update's version, so its value remains the same as before.
          progressReceiver.evaluated(nodeKey, nodeValue,
              nodeVersion < graphVersion ? EvaluationState.CLEAN : EvaluationState.BUILT);
        }
        signalNodesAndEnqueueIfReady(visitor, reverseDeps, nodeVersion);
      }

      visitor.notifyDone(nodeKey);
      replayingNestedSetEventVisitor.visit(events);
    }

    @Nullable
    private String getTagFromKey() {
      return nodeBuilders.get(nodeKey.getNodeType()).extractTag(nodeKey);
    }

    @Override
    public CountDownLatch getExceptionLatchForTesting() {
      return visitor.getExceptionLatchForTestingOnly();
    }
  }

  /** Class to signal that no exception should be thrown when retrieving a node. */
  private class DontThrowAnyException extends RuntimeException {}


  @Nullable
  private static Node getNodeFromNOE(NodeOrException<DontThrowAnyException> nOE) {
    try {
      return nOE.get();
    } catch (Throwable e) {
      // Should never get here -- this particular NodeOrException.get() should never throw.
      throw new IllegalStateException(e);
    }
  }

  private static final Function<NodeOrException<DontThrowAnyException>, Node> GET_NODE_FROM_NOE =
      new Function<NodeOrException<DontThrowAnyException>, Node>() {
    @Override
    public Node apply(NodeOrException<DontThrowAnyException> nOE) {
      return getNodeFromNOE(nOE);
    }
  };

  private class NodeVisitor extends AbstractQueueVisitor {
    private final Set<NodeKey> inflightNodes = Sets.newConcurrentHashSet();

    private NodeVisitor(int threadCount) {
      super(/*concurrent*/true,
          threadCount,
          threadCount,
          1, TimeUnit.SECONDS,
          /*failFastOnException*/true,
          /*failFastOnInterrupt*/true,
          "skyframe-evaluator");
    }

    @Override
    protected boolean isCriticalError(Throwable e) {
      return e instanceof RuntimeException;
    }

    protected void waitForCompletion() throws InterruptedException {
      work(/*failFastOnInterrupt=*/true);
    }

    public void enqueueEvaluation(final NodeKey key) {
      if (inflightNodes.add(key) && progressReceiver != null) {
        progressReceiver.enqueueing(key);
      }
      enqueue(new Evaluate(this, key));
    }

    public void notifyDone(NodeKey key) {
      inflightNodes.remove(key);
    }

    private boolean isInflight(NodeKey key) {
      return inflightNodes.contains(key);
    }

    private void clean() {
      // TODO(bazel-team): In nokeep_going mode or in case of an interrupt, we need to remove
      // partial nodes from the graph. Find a better way to handle those cases.
      for (NodeKey node : inflightNodes) {
        NodeEntry entry = graph.get(node);
        if (entry.isDone()) {
          // Entry may be done in case of a RuntimeException or other programming bug. Do nothing,
          // since (a) we're about to crash anyway, and (b) getTemporaryDirectDeps cannot be called
          // on a done node, so the call below would crash, which would mask the actual exception
          // that caused this state.
          continue;
        }
        Set<NodeKey> temporaryDeps = entry.getTemporaryDirectDeps();
        graph.remove(node);
        for (NodeKey dep : temporaryDeps) {
          NodeEntry nodeEntry = graph.get(dep);
          if (nodeEntry != null) {  // TODO(bazel-team): Is this right?
            // Don't crash here if we are about to crash anyway. This crash would mask the other.
            nodeEntry.removeReverseDep(node);
          }
        }
      }
    }
  }

  /**
   * An action that evaluates a node.
   */
  private class Evaluate implements Runnable {
    private final NodeVisitor visitor;
    /** The name of the node to be evaluated. */
    private final NodeKey nodeKey;

    private Evaluate(NodeVisitor visitor, NodeKey nodeKey) {
      this.visitor = visitor;
      this.nodeKey = nodeKey;
    }

    private void enqueueChild(NodeKey nodeKey, NodeEntry entry, NodeKey child) {
      Preconditions.checkState(!entry.isDone(), "%s %s", nodeKey, entry);
      Preconditions.checkState(!ErrorTransienceNode.key().equals(child),
          "%s cannot request ErrorTransienceNode as a dep: %s", nodeKey, entry);

      NodeEntry depEntry = graph.createIfAbsent(child);
      switch (depEntry.addReverseDepAndCheckIfDone(nodeKey)) {
        case DONE :
          if (entry.signalDep(depEntry.getVersion())) {
            // This can only happen if there are no more children to be added.
            visitor.enqueueEvaluation(nodeKey);
          }
          break;
        case ADDED_DEP :
          break;
        case NEEDS_SCHEDULING :
          visitor.enqueueEvaluation(child);
          break;
      }
    }

    @Override
    public void run() {
      NodeEntry state = graph.get(nodeKey);
      Preconditions.checkNotNull(state, "%s %s", nodeKey, state);
      Preconditions.checkState(state.isReady(), "%s %s", nodeKey, state);

      if (state.isDirty()) {
        switch (state.getDirtyState()) {
          case CHECK_DEPENDENCIES:
            // Evaluating a dirty node for the first time, and checking its children to see if any
            // of them have changed. Note that there must be dirty children for this to happen.

            // Check the children group by group -- we don't want to evaluate a node that is no
            // longer needed because an earlier dependency changed. For example, //foo:foo depends
            // on target //bar:bar and is built. Then foo/BUILD is modified to remove the dependence
            // on bar, and bar/BUILD is deleted. Reloading //bar:bar would incorrectly throw an
            // exception. To avoid this, we must reload foo/BUILD first, at which point we will
            // discover that it has changed, and re-evaluate target //foo:foo from scratch.
            // On the other hand, when an action requests all of its inputs, we can safely check all
            // of them in parallel on a subsequent build. So we allow checking an entire group in
            // parallel here, if the node builder requested a group last build.
            for (NodeKey directDep : state.getNextDirtyDirectDeps()) {
              enqueueChild(nodeKey, state, directDep);
            }
            return;
          case VERIFIED_CLEAN:
            // No child has a changed value. This node can be marked done and its parents signaled
            // without any re-evaluation.
            visitor.notifyDone(nodeKey);
            Set<NodeKey> reverseDeps = state.markClean();
            if (progressReceiver != null) {
              // Tell the receiver that the node was not actually changed this run.
              progressReceiver.evaluated(nodeKey, state.getNode(), EvaluationState.CLEAN);
            }
            signalNodesAndEnqueueIfReady(visitor, reverseDeps, state.getVersion());
            return;
          case REBUILDING:
            // Nothing to be done if we are already rebuilding.
        }
      }

      // TODO(bazel-team): Once deps are requested in a deterministic order within a group, or the
      // framework is resilient to rearranging group order, change this so that
      // NodeBuilderEnvironment "follows along" as the node builder runs, iterating through the
      // direct deps that were requested on a previous run. This would allow us to avoid the
      // conversion of the direct deps into a set.
      Set<NodeKey> directDeps = state.getTemporaryDirectDeps();
      Preconditions.checkState(!directDeps.contains(ErrorTransienceNode.key()),
          "%s cannot have a dep on ErrorTransienceNode during building: %s", nodeKey, state);
      // Get the corresponding node builder and call it on this node.
      NodeBuilderEnvironment env = new NodeBuilderEnvironment(nodeKey, directDeps, visitor);

      NodeType nodeType = nodeKey.getNodeType();
      NodeBuilder factory = nodeBuilders.get(nodeType);
      Preconditions.checkState(factory != null, "%s %s", nodeType, state);

      Node node;
      Profiler.instance().startTask(ProfilerTask.SKYFRAME_NODE_BUILDER, nodeKey);
      try {
        // TODO(bazel-team): count how many of these calls returns null vs. non-null
        node = factory.build(nodeKey, env);
      } catch (final NodeBuilderException builderException) {
        registerNewlyDiscoveredDepsForDoneEntry(nodeKey, state, env);
        env.setError(new ErrorInfo(builderException));
        env.commit(/*enqueueParents=*/keepGoing);
        if (keepGoing) {
          return;
        }
        throw SchedulerException.ofError(new ErrorInfo(builderException), nodeKey);
      } catch (InterruptedException ie) {
        // InterruptedException cannot be thrown by Runnable.run, so we must wrap it.
        // Interrupts can be caught by both the Evaluator and the AbstractQueueVisitor.
        // The former will unwrap the IE and propagate it as is; the latter will throw a new IE.
        throw SchedulerException.ofInterruption(ie, nodeKey);
      } catch (RuntimeException re) {
        // Programmer error (most likely NPE or a failed precondition in a NodeBuilder). Output
        // some context together with the exception.
        String msg = prepareCrashMessage(nodeKey, state.getInProgressReverseDeps());
        throw new RuntimeException(msg, re);
      } finally {
        env.doneBuilding();
        Profiler.instance().completeTask(ProfilerTask.SKYFRAME_NODE_BUILDER);
      }

      GroupedListHelper<NodeKey> newDirectDeps = env.newlyRequestedDeps;

      if (node != null) {
        Preconditions.checkState(!env.depsMissing,
            "%s -> %s, NodeEntry: %s", nodeKey, newDirectDeps, state);
        env.setNodeValue(node);
        registerNewlyDiscoveredDepsForDoneEntry(nodeKey, state, env);
        env.commit(/*enqueueParents=*/true);
        return;
      }

      // TODO(bazel-team): This code is not safe to interrupt, because we would lose the state in
      // newDirectDeps.

      // TODO(bazel-team): An ill-behaved NodeBuilder can throw us into an infinite loop where we
      // add more dependencies on every run. [skyframe-core]

      // Add all new keys to the set of known deps.
      state.addTemporaryDirectDeps(newDirectDeps);

      // If there were no newly requested dependencies, at least one of them was in error or there
      // is a bug in the NodeBuilder implementation. The environment has collected its errors, so we
      // just order it to be built.
      if (newDirectDeps.isEmpty()) {
        // TODO(bazel-team): This means a bug in the NodeBuilder. What to do?
        Preconditions.checkState(!env.childErrorInfos.isEmpty(), "%s %s", nodeKey, state);
        env.commit(/*enqueueParents=*/keepGoing);
        if (!keepGoing) {
          throw SchedulerException.ofError(state.getErrorInfo(), nodeKey);
        }
        return;
      }
      for (NodeKey newDirectDep : newDirectDeps) {
        enqueueChild(nodeKey, state, newDirectDep);
      }
      // It is critical that there is no code below this point.
    }

    private String prepareCrashMessage(NodeKey nodeKey, Iterable<NodeKey> reverseDeps) {
      StringBuilder reverseDepDump = new StringBuilder();
      for (NodeKey key : reverseDeps) {
        if (reverseDepDump.length() > MAX_REVERSEDEP_DUMP_LENGTH) {
          reverseDepDump.append(", ...");
          break;
        }
        if (reverseDepDump.length() > 0) {
          reverseDepDump.append(", ");
        }
        reverseDepDump.append("'");
        reverseDepDump.append(key.toString());
        reverseDepDump.append("'");
      }

      return String.format(
          "Unrecoverable error while evaluating node '%s' (requested by nodes %s)",
          nodeKey, reverseDepDump);
    }

    private static final int MAX_REVERSEDEP_DUMP_LENGTH = 1000;
  }

  /**
   * Signals all parents that this node is finished. If visitor is not null, also enqueues any
   * parents that are ready. If visitor is null, indicating that we are building this node after the
   * main build aborted, then skip any parents that are already done (that can happen with cycles).
   */
  private void signalNodesAndEnqueueIfReady(@Nullable NodeVisitor visitor, Iterable<NodeKey> keys,
      long version) {
    if (visitor != null) {
      for (NodeKey key : keys) {
        if (graph.get(key).signalDep(version)) {
          visitor.enqueueEvaluation(key);
        }
      }
    } else {
      for (NodeKey key : keys) {
        NodeEntry entry = Preconditions.checkNotNull(graph.get(key), key);
        if (!entry.isDone()) {
          // In cycles, we can have parents that are already done.
          entry.signalDep(version);
        }
      }
    }
  }

  /**
   * If child is not done, removes key from child's reverse deps. Returns whether child should be
   * removed from key's entry's direct deps.
   */
  private boolean removeIncompleteChild(NodeKey key, NodeKey child) {
    NodeEntry childEntry = graph.get(child);
    if (!isDoneForBuild(childEntry)) {
      childEntry.removeReverseDep(key);
      return true;
    }
    return false;
  }

  /**
   * Add any additional deps that were registered during the run of a builder that finished by
   * creating a node or throwing an error. Builders may throw errors even if all their deps were not
   * provided -- we trust that a NodeBuilder may be know it should throw an error even if not all of
   * its requested deps are done. However, that means we're assuming the NodeBuilder would throw
   * that same error if all of its requested deps were done. Unfortunately, there is no way to
   * enforce that condition.
   */
  private void registerNewlyDiscoveredDepsForDoneEntry(NodeKey nodeKey, NodeEntry entry,
      NodeBuilderEnvironment env) {
    Set<NodeKey> unfinishedDeps = new HashSet<>();
    Iterables.addAll(unfinishedDeps,
        Iterables.filter(env.newlyRequestedDeps, Predicates.not(nodeEntryIsDone)));
    env.newlyRequestedDeps.remove(unfinishedDeps);
    entry.addTemporaryDirectDeps(env.newlyRequestedDeps);
    for (NodeKey newDep : env.newlyRequestedDeps) {
      NodeEntry depEntry = graph.get(newDep);
      DependencyState triState = depEntry.addReverseDepAndCheckIfDone(nodeKey);
      Preconditions.checkState(DependencyState.DONE == triState,
          "new dep %s was not already done for %s. NodeEntry: %s. DepNodeEntry: %s",
          newDep, nodeKey, entry, depEntry);
      entry.signalDep();
    }
    Preconditions.checkState(entry.isReady(), "%s %s %s", nodeKey, entry, env.newlyRequestedDeps);
  }

  @Override
  @ThreadCompatible
  public <T extends Node> UpdateResult<T> eval(Iterable<NodeKey> nodeKeys)
      throws InterruptedException {
    ImmutableSet<NodeKey> nodeKeySet = ImmutableSet.copyOf(nodeKeys);

    // Optimization: if all required node values are already present in the cache, return them
    // directly without launching the heavy machinery, spawning threads, etc.
    if (Iterables.all(nodeKeySet, nodeEntryIsDone)) {
      return constructResult(null, nodeKeySet, null);
    }

    Profiler.instance().startTask(ProfilerTask.SKYFRAME_EVAL, nodeKeySet);
    try {
      return eval(nodeKeySet, new NodeVisitor(threadCount));
    } finally {
      Profiler.instance().completeTask(ProfilerTask.SKYFRAME_EVAL);
    }
  }

  @ThreadCompatible
  private <T extends Node> UpdateResult<T> eval(ImmutableSet<NodeKey> nodeKeys,
      NodeVisitor visitor) throws InterruptedException {
    // We unconditionally add the ErrorTransienceNode here, to ensure that it will be created, and
    // in the graph, by the time that it is needed. Creating it on demand in a parallel context sets
    // up a race condition, because there is no way to atomically create a node and set its value.
    NodeEntry errorTransienceEntry = graph.createIfAbsent(ErrorTransienceNode.key());
    DependencyState triState = errorTransienceEntry.addReverseDepAndCheckIfDone(null);
    Preconditions.checkState(triState != DependencyState.ADDED_DEP,
        "%s %s", errorTransienceEntry, triState);
    if (triState != DependencyState.DONE) {
      errorTransienceEntry.setValue(new ErrorTransienceNode(), graphVersion);
      Preconditions.checkState(
          errorTransienceEntry.addReverseDepAndCheckIfDone(null) != DependencyState.ADDED_DEP,
          errorTransienceEntry);
    }
    for (NodeKey nodeKey : nodeKeys) {
      NodeEntry entry = graph.createIfAbsent(nodeKey);
      // This must be equivalent to the code in enqueueChild above, in order to be thread-safe.
      switch (entry.addReverseDepAndCheckIfDone(null)) {
        case NEEDS_SCHEDULING:
          visitor.enqueueEvaluation(nodeKey);
          break;
        case DONE:
          if (progressReceiver != null) {
            Node node = entry.getNode();
            if (node != null) {
              // Nodes with errors will have no value. Don't inform the receiver in that case.
              // For most nodes we do not inform the progress receiver if they were already done
              // when we retrieve them, but top-level nodes are presumably of more interest.
              progressReceiver.evaluated(nodeKey, node, entry.getVersion() < graphVersion
                  ? EvaluationState.CLEAN
                  : EvaluationState.BUILT);
            }
          }
          break;
        case ADDED_DEP:
          break;
        default:
          throw new IllegalStateException(entry + " for " + nodeKey + " in unknown state");
      }
    }
    try {
      return waitForCompletionAndConstructResult(visitor, nodeKeys);
    } finally {
      visitor.clean();
    }
  }

  private <T extends Node> UpdateResult<T> waitForCompletionAndConstructResult(
      NodeVisitor visitor, Iterable<NodeKey> nodeKeys) throws InterruptedException {
    Map<NodeKey, NodeWithMetadata> bubbleErrorInfo = null;
    try {
      visitor.waitForCompletion();
    } catch (final SchedulerException e) {
      Throwables.propagateIfPossible(e.getCause(), InterruptedException.class);
      if (Thread.interrupted()) {
        // As per the contract of AbstractQueueVisitor#work, if an unchecked exception is thrown and
        // the build is interrupted, the thrown exception is what will be rethrown. Since the user
        // presumably wanted to interrupt the build, we ignore the thrown SchedulerException (which
        // doesn't indicate a programming bug) and throw an InterruptedException.
        throw new InterruptedException();
      }

      NodeKey errorKey = Preconditions.checkNotNull(e.getFailedNode(), e);
      // ErrorInfo could only be null if SchedulerException wrapped an InterruptedException, but
      // that should have been propagated.
      ErrorInfo errorInfo = Preconditions.checkNotNull(e.getErrorInfo(), errorKey);
      bubbleErrorInfo = bubbleErrorUp(errorInfo, errorKey, nodeKeys, visitor);
    }

    // Successful evaluation, either because keepGoing or because we actually did succeed.
    // TODO(bazel-team): Maybe report root causes during the build for lower latency.
    return constructResult(visitor, nodeKeys, bubbleErrorInfo);
  }

  /**
   * Walk up graph to find a toplevel node that wanted this failure (or all such nodes if
   * {@code keepGoing=true}). Store the failed nodes along the way in a map, with ErrorInfos that
   * are appropriate for that layer.
   * Example:
   *                      foo   bar
   *                        \   /
   *           unrequested   baz
   *                     \    |
   *                      failed-node
   * User requests foo, bar. When failed-node fails, we look at its parents. unrequested is not
   * in-flight, so we replace failed-node by baz and repeat. We look at baz's parents. foo is
   * in-flight, so we replace baz by foo. Since foo is a toplevel node, we then break, since we
   * know a top-level node, foo, that depended on the failed node.
   *
   * There's the potential for a weird "track jump" here in the case:
   *                        foo
   *                       / \
   *                   fail1 fail2
   * If fail1 and fail2 fail simultaneously, fail2 may start propagating up in the loop below.
   * However, foo requests fail1 first, and then throws an exception based on that. This is not
   * incorrect, but may be unexpected.
   *
   * <p>Returns a map of errors that have been constructed during the bubbling up, so that the
   * appropriate error can be returned to the caller, even though that error was not written to the
   * graph. If a cycle is detected during the bubbling, this method aborts and returns null so that
   * the normal cycle detection can handle the cycle.
   */
  private Map<NodeKey, NodeWithMetadata> bubbleErrorUp(final ErrorInfo leafFailure,
      NodeKey errorKey, Iterable<NodeKey> nodeKeys, NodeVisitor visitor) {
    Set<NodeKey> rootNodes = ImmutableSet.copyOf(nodeKeys);
    ErrorInfo error = leafFailure;
    Map<NodeKey, NodeWithMetadata> bubbleErrorInfo = new HashMap<>();
    boolean externalInterrupt = false;
    while (!rootNodes.contains(errorKey)) {
      NodeEntry errorEntry = graph.get(errorKey);
      Iterable<NodeKey> reverseDeps = errorEntry.isDone()
          ? errorEntry.getReverseDeps()
          : errorEntry.getInProgressReverseDeps();
      NodeKey parent = Iterables.getFirst(reverseDeps, null);
      Preconditions.checkNotNull(parent, "", errorKey, bubbleErrorInfo);
      if (bubbleErrorInfo.containsKey(parent)) {
        // We are in a cycle. Don't try to bubble anything up -- cycle detection will kick in.
        return null;
      }
      NodeEntry parentEntry = Preconditions.checkNotNull(graph.get(parent),
          "parent %s of %s not in graph", parent, errorKey);
      if (parentEntry.isDone()) {
        // In the rare case that the error node signaled its parent before throwing and the
        // parent restarted itself before the error node threw, the parent may already be done.
        // In that case, it essentially built itself as it would have here, so we continue
        // the walk of the graph with it.
        error = Preconditions.checkNotNull(parentEntry.getErrorInfo(),
            "%s was done with no error but child %s had error. NodeEntry: %s",
            parent, errorKey, parentEntry);
        errorKey = parent;
        continue;
      }
      Preconditions.checkState(visitor.isInflight(parent), "%s %s", parent, parentEntry);
      errorKey = parent;
      NodeBuilder factory = nodeBuilders.get(parent.getNodeType());
      if (parentEntry.isDirty()) {
        switch (parentEntry.getDirtyState()) {
          case CHECK_DEPENDENCIES:
            // If this node's child was bubbled up to, it did not signal this node, and so we must
            // manually make it ready to build.
            parentEntry.signalDep();
            // Fall through to REBUILDING, since state is now REBUILDING.
          case REBUILDING:
            // Nothing to be done.
            break;
          default:
            throw new AssertionError(parent + " not in valid dirty state: " + parentEntry);
        }
      }
      NodeBuilderEnvironment env =
          new NodeBuilderEnvironment(parent, parentEntry.getTemporaryDirectDeps(),
              bubbleErrorInfo, visitor);
      externalInterrupt = externalInterrupt || Thread.currentThread().isInterrupted();
      try {
        // This build is only to check if the parent node can give us a better error. We don't
        // care about a return value.
        factory.build(parent, env);
      } catch (NodeBuilderException builderException) {
        error = new ErrorInfo(builderException);
        bubbleErrorInfo.put(errorKey,
            NodeWithMetadata.error(new ErrorInfo(errorKey, ImmutableSet.of(error)),
                env.buildEvents(/*missingChildren=*/true)));
        continue;
      } catch (InterruptedException interruptedException) {
        // Do nothing.
        // This throw happens if the builder requested the failed node, and then checked the
        // interrupted state later -- getDepOrThrow sets the interrupted bit after the failed
        // node is requested, to prevent the builder from doing too much work.
      } finally {
        // Clear interrupted status. We're not listening to interrupts here.
        Thread.interrupted();
      }
      // Builder didn't throw an exception, so just propagate this one up.
      bubbleErrorInfo.put(errorKey,
          NodeWithMetadata.error(new ErrorInfo(errorKey, ImmutableSet.of(error)),
              env.buildEvents(/*missingChildren=*/true)));
    }

    // Reset the interrupt bit if there was an interrupt from outside this evaluator interrupt.
    // Note that there are internal interrupts set in the node builder environment if an error
    // bubbling node calls getDepOrThrow() on a node in error.
    if (externalInterrupt) {
      Thread.currentThread().interrupt();
    }
    return bubbleErrorInfo;
  }

  /**
   * Constructs an {@link UpdateResult} from the {@link #graph}.  Looks for cycles if there
   * are unfinished nodes but no error was already found through bubbling up
   * (as indicated by {@code bubbleErrorInfo} being null).
   *
   * <p>{@code visitor} may be null, but only in the case where all graph entries corresponding to
   * {@code nodeKeys} are known to be in the DONE state ({@code entry.isDone()} returns true).
   */
  private <T extends Node> UpdateResult<T> constructResult(
      @Nullable NodeVisitor visitor, Iterable<NodeKey> nodeKeys,
      Map<NodeKey, NodeWithMetadata> bubbleErrorInfo) {
    Preconditions.checkState(!keepGoing || bubbleErrorInfo == null, "", nodeKeys, bubbleErrorInfo);
    UpdateResult.Builder<T> result = UpdateResult.builder();
    List<NodeKey> cycleRoots = new ArrayList<>();
    boolean hasError = false;
    for (NodeKey nodeKey : nodeKeys) {
      NodeWithMetadata value = getValueMaybeFromError(nodeKey, bubbleErrorInfo);
      // Cycle checking: if there is a cycle, evaluation cannot progress, therefore,
      // the final nodes will not be in DONE state when the work runs out.
      if (value == null) {
        // Don't look for cycles if the build failed for a known reason.
        if (bubbleErrorInfo == null) {
          cycleRoots.add(nodeKey);
        }
        hasError = true;
        continue;
      }
      Node node = value.getNode();
      // TODO(bazel-team): Verify that message replay is fast and works in failure
      // modes [skyframe-core]
      // Note that replaying events here is only necessary on null builds, because otherwise we
      // would have already printed the transitive messages after building these nodes.
      replayingNestedSetEventVisitor.visit(value.getTransitiveEvents());
      ErrorInfo errorInfo = value.getErrorInfo();
      Preconditions.checkState(node != null || errorInfo != null, nodeKey);
      hasError = hasError || (errorInfo != null);
      if (!keepGoing && errorInfo != null) {
        // value will be null here unless the node was already built on a prior keepGoing build.
        result.addError(nodeKey, errorInfo);
        continue;
      }
      if (node == null) {
        // Note that we must be in the keepGoing case. Only make this node an error if it doesn't
        // have a value. The error shouldn't matter to the caller since the node succeeded after a
        // fashion.
        result.addError(nodeKey, errorInfo);
      } else {
        result.addResult(nodeKey, node);
      }
    }
    if (!cycleRoots.isEmpty()) {
      Preconditions.checkState(visitor != null, nodeKeys);
      checkForCycles(cycleRoots, result, visitor, keepGoing);
    }
    Preconditions.checkState(bubbleErrorInfo == null || hasError,
        "If an error bubbled up, some top-level node must be in error", bubbleErrorInfo, nodeKeys);
    result.setHasError(hasError);
    return result.build();
  }

  private <T extends Node> void checkForCycles(
      Iterable<NodeKey> badRoots, UpdateResult.Builder<T> result, final NodeVisitor visitor,
      boolean keepGoing) {
    for (NodeKey root : badRoots) {
      ErrorInfo errorInfo = checkForCycles(root, visitor, keepGoing);
      if (errorInfo == null) {
        // This node just wasn't finished when evaluation aborted -- there were no cycles below it.
        Preconditions.checkState(!keepGoing, "", root, badRoots);
        continue;
      }
      Preconditions.checkState(!Iterables.isEmpty(errorInfo.getCycleInfo()),
          "%s was not evaluated, but was not part of a cycle", root);
      result.addError(root, errorInfo);
      if (!keepGoing) {
        return;
      }
    }
  }

  /**
   * Marker node that we push onto a stack before we push a node's children on. When the marker
   * node is popped, we know that all the children are finished. We would use null instead, but
   * ArrayDeque does not permit null elements.
   */
  private static final NodeKey CHILDREN_FINISHED =
      new NodeKey(new NodeType("MARKER", false), "MARKER");

  /** The max number of cycles we will report to the user for a given root, to avoid OOMing. */
  private static final int MAX_CYCLES = 20;

  /**
   * The algorithm for this cycle detector is as follows. We visit the graph depth-first, keeping
   * track of the path we are currently on. We skip any DONE nodes (they are transitively
   * error-free). If we come to a node already on the path, we immediately construct a cycle. If
   * we are in the noKeepGoing case, we return ErrorInfo with that cycle to the caller. Otherwise,
   * we continue. Once all of a node's children are done, we construct an error node for it, based
   * on those children. Finally, when the original root's node is constructed, we return its
   * ErrorInfo.
   */
  private ErrorInfo checkForCycles(NodeKey root, NodeVisitor visitor, boolean keepGoing) {
    // The number of cycles found. Do not keep on searching for more cycles after this many were
    // found.
    int cyclesFound = 0;
    // The path through the graph currently being visited.
    List<NodeKey> graphPath = new ArrayList<>();
    // Set of nodes on the path, to avoid expensive searches through the path for cycles.
    Set<NodeKey> pathSet = new HashSet<>();

    // Maintain a stack explicitly instead of recursion to avoid stack overflows
    // on extreme graphs (with long dependency chains).
    Deque<NodeKey> toVisit = new ArrayDeque<>();

    toVisit.push(root);

    // The procedure for this check is as follows: we visit a node, push it onto the graph stack,
    // push a marker node onto the toVisit stack, and then push all of its children onto the toVisit
    // stack. Thus, when the marker node comes to the top of the toVisit stack, we have visited the
    // downward transitive closure of the node. At that point, all of its children must be finished,
    // and so we can build the definitive error info for the node, popping it off the graph stack.
    while (!toVisit.isEmpty()) {
      NodeKey key = toVisit.pop();
      NodeEntry entry = graph.get(key);

      if (key == CHILDREN_FINISHED) {
        // A marker value means we are done with all children of a node. Since all nodes have
        // errors, we must have found errors in the children when that happens.
        key = graphPath.remove(graphPath.size() - 1);
        entry = graph.get(key);
        pathSet.remove(key);
        // Skip this node if it was first/last node of a cycle, and so has already been processed.
        if (entry.isDone()) {
          continue;
        }
        if (!keepGoing) {
          // in the --nokeep_going mode, we would have already returned if we'd found a cycle below
          // this node. The fact that we haven't means that there were no cycles below this node --
          // it just hadn't finished evaluating. So skip it.
          continue;
        }
        if (cyclesFound < MAX_CYCLES) {
          // Node must be ready, because all of its children have finished, so we can build its
          // error.
          Preconditions.checkState(entry.isReady(), "%s not ready. NodeEntry: %s", key, entry);
        } else if (!entry.isReady()) {
          removeIncompleteChildrenForCycle(key, entry, entry.getTemporaryDirectDeps());
        }
        Set<NodeKey> directDeps = entry.getTemporaryDirectDeps();
        // Find out which children have errors. Similar logic to that in Evaluate#run().
        List<ErrorInfo> errorDeps = getChildrenErrorsForCycle(directDeps);
        Preconditions.checkState(!errorDeps.isEmpty(),
            "Node %s was not successfully evaluated, but had no child errors. NodeEntry: %s", key,
            entry);
        NodeBuilderEnvironment env = new NodeBuilderEnvironment(key, directDeps, visitor);
        env.setError(new ErrorInfo(key, errorDeps));
        env.commit(/*enqueueParents=*/false);
      }

      // Nothing to be done for this node if it already has an entry.
      if (entry.isDone()) {
        continue;
      }
      if (cyclesFound == MAX_CYCLES) {
        // Do not keep on searching for cycles indefinitely, to avoid excessive runtime/OOMs.
        continue;
      }

      if (pathSet.contains(key)) {
        int cycleStart = graphPath.indexOf(key);
        // Found a cycle!
        cyclesFound++;
        Iterable<NodeKey> cycle = graphPath.subList(cycleStart, graphPath.size());
        // Put this node into a consistent state for building if it is dirty.
        if (entry.isDirty() && entry.getDirtyState() == DirtyState.CHECK_DEPENDENCIES) {
          // In the check deps state, entry has exactly one child not done yet. Note that this node
          // must be part of the path to the cycle we have found (since done nodes cannot be in
          // cycles, and this is the only missing one). Thus, it will not be removed below in
          // removeDescendantsOfCycleNode, so it is safe here to signal that it is done.
          entry.signalDep();
        }
        if (keepGoing) {
          // Any children of this node that we haven't already visited are not worth visiting,
          // since this node is about to be done. Thus, the only child worth visiting is the one in
          // this cycle, the cycleChild (which may == key if this cycle is a self-edge).
          NodeKey cycleChild = selectCycleChild(key, graphPath, cycleStart);
          removeDescendantsOfCycleNode(key, entry, cycleChild, toVisit,
                  graphPath.size() - cycleStart);
          NodeWithMetadata dummyValue = NodeWithMetadata.wrapWithMetadata(new Node() {});


          NodeBuilderEnvironment env =
              new NodeBuilderEnvironment(key, entry.getTemporaryDirectDeps(),
                  ImmutableMap.of(cycleChild, dummyValue), visitor);

          // Construct error info for this node. Get errors from children, which are all done except
          // possibly for the cycleChild.
          List<ErrorInfo> allErrors =
              getChildrenErrors(entry.getTemporaryDirectDeps(), /*unfinishedChild=*/cycleChild);
          CycleInfo cycleInfo = new CycleInfo(cycle);
          // Add in this cycle.
          allErrors.add(new ErrorInfo(cycleInfo));
          env.setError(new ErrorInfo(key, allErrors));
          env.commit(/*enqueueParents=*/false);
          continue;
        } else {
          // We need to return right away in the noKeepGoing case, so construct the cycle (with the
          // path) and return.
          Preconditions.checkState(graphPath.get(0).equals(root),
              "%s not reached from %s. NodeEntry: %s", key, root, entry);
          return new ErrorInfo(new CycleInfo(graphPath.subList(0, cycleStart), cycle));
        }
      }

      // This node is not yet known to be in a cycle. So process its children.
      Iterable<? extends NodeKey> children = graph.get(key).getTemporaryDirectDeps();
      if (Iterables.isEmpty(children)) {
        continue;
      }

      // This marker flag will tell us when all this node's children have been processed.
      toVisit.push(CHILDREN_FINISHED);
      // This node is now part of the path through the graph.
      graphPath.add(key);
      pathSet.add(key);
      for (NodeKey nextNode : children) {
        toVisit.push(nextNode);
      }
    }
    return keepGoing ? getAndCheckDone(root).getErrorInfo() : null;
  }

  /**
   * Returns the child of this node that is in the cycle that was just found. If the cycle is a
   * self-edge, returns the node itself.
   */
  private static NodeKey selectCycleChild(NodeKey key, List<NodeKey> graphPath, int cycleStart) {
    return cycleStart + 1 == graphPath.size() ? key : graphPath.get(cycleStart + 1);
  }

  /**
   * Get all the errors of child nodes. There must be at least one cycle amongst them.
   *
   * @param children child nodes to query for errors.
   * @return List of ErrorInfos from all children that had errors.
   */
  private List<ErrorInfo> getChildrenErrorsForCycle(Iterable<NodeKey> children) {
    List<ErrorInfo> allErrors = new ArrayList<>();
    boolean foundCycle = false;
    for (NodeKey child : children) {
      ErrorInfo errorInfo = getAndCheckDone(child).getErrorInfo();
      if (errorInfo != null) {
        foundCycle |= !Iterables.isEmpty(errorInfo.getCycleInfo());
        allErrors.add(errorInfo);
      }
    }
    Preconditions.checkState(foundCycle, "", children, allErrors);
    return allErrors;
  }

  /**
   * Get all the errors of child nodes.
   *
   * @param children child nodes to query for errors.
   * @param unfinishedChild child which is allowed to not be done.
   * @return List of ErrorInfos from all children that had errors.
   */
  private List<ErrorInfo> getChildrenErrors(Iterable<NodeKey> children, NodeKey unfinishedChild) {
    List<ErrorInfo> allErrors = new ArrayList<>();
    for (NodeKey child : children) {
      ErrorInfo errorInfo = getErrorMaybe(child, /*allowUnfinished=*/child.equals(unfinishedChild));
      if (errorInfo != null) {
        allErrors.add(errorInfo);
      }
    }
    return allErrors;
  }

  @Nullable
  private ErrorInfo getErrorMaybe(NodeKey key, boolean allowUnfinished) {
    if (!allowUnfinished) {
      return getAndCheckDone(key).getErrorInfo();
    }
    NodeEntry entry = Preconditions.checkNotNull(graph.get(key), key);
    return entry.isDone() ? entry.getErrorInfo() : null;
  }

  /**
   * Removes direct children of key from toVisit and from the entry itself, and makes the entry
   * ready if necessary. We must do this because it would not make sense to try to build the
   * children after building the entry. It would violate the invariant that a parent can only be
   * built after its children are built; See bug "Precondition error while evaluating a Skyframe
   * graph with a cycle".
   *
   * @param key NodeKey of node in a cycle.
   * @param entry NodeEntry of node in a cycle.
   * @param cycleChild direct child of key in the cycle, or key itself if the cycle is a self-edge.
   * @param toVisit list of remaining nodes to visit by the cycle-checker.
   * @param cycleLength the length of the cycle found.
   */
  private void removeDescendantsOfCycleNode(NodeKey key, NodeEntry entry,
      @Nullable NodeKey cycleChild, Iterable<NodeKey> toVisit, int cycleLength) {
    Set<NodeKey> unvisitedDeps = new HashSet<>(entry.getTemporaryDirectDeps());
    unvisitedDeps.remove(cycleChild);
    // Remove any children from this node that are not part of the cycle we just found. They are
    // irrelevant to the node as it stands, and if they are deleted from the graph because they are
    // not built by the end of cycle-checking, we would have dangling references.
    removeIncompleteChildrenForCycle(key, entry, unvisitedDeps);
    if (!entry.isReady()) {
      // The entry has at most one undone dep now, its cycleChild. Signal to make entry ready. Note
      // that the entry can conceivably be ready if its cycleChild already found a different cycle
      // and was built.
      entry.signalDep();
    }
    Preconditions.checkState(entry.isReady(), "%s %s %s", key, cycleChild, entry);
    Iterator<NodeKey> it = toVisit.iterator();
    while (it.hasNext()) {
      NodeKey descendant = it.next();
      if (descendant == CHILDREN_FINISHED) {
        // Marker node, delineating the end of a group of children that were enqueued.
        cycleLength--;
        if (cycleLength == 0) {
          // We have seen #cycleLength-1 marker nodes, and have arrived at the one for this node, so
          // we are done.
          return;
        }
        continue; // Don't remove marker nodes.
      }
      if (cycleLength == 1) {
        // Remove the direct children remaining to visit of the cycle node.
        Preconditions.checkState(unvisitedDeps.contains(descendant),
            "%s %s %s %s %s", key, descendant, cycleChild, unvisitedDeps, entry);
        it.remove();
      }
    }
    throw new IllegalStateException("There were not " + cycleLength + " groups of children in "
        + toVisit + " when trying to remove children of " + key + " other than " + cycleChild);
  }

  private void removeIncompleteChildrenForCycle(NodeKey key, NodeEntry entry,
      Iterable<NodeKey> children) {
    Set<NodeKey> unfinishedDeps = new HashSet<>();
    for (NodeKey child : children) {
      if (removeIncompleteChild(key, child)) {
        unfinishedDeps.add(child);
      }
    }
    entry.removeUnfinishedDeps(unfinishedDeps);
  }

  private NodeEntry getAndCheckDone(NodeKey key) {
    NodeEntry entry = graph.get(key);
    Preconditions.checkNotNull(entry, key);
    Preconditions.checkState(entry.isDone(), "%s %s", key, entry);
    return entry;
  }

  private NodeWithMetadata getValueMaybeFromError(NodeKey key,
      @Nullable Map<NodeKey, NodeWithMetadata> bubbleErrorInfo) {
    Node value = bubbleErrorInfo == null ? null : bubbleErrorInfo.get(key);
    NodeEntry entry = graph.get(key);
    if (value != null) {
      Preconditions.checkNotNull(entry,
          "Node cannot have error before evaluation started", key, value);
      return NodeWithMetadata.wrapWithMetadata(value);
    }
    return isDoneForBuild(entry) ? entry.getNodeWithMetadata() : null;
  }

  /**
   * Return true if the entry does not need to be re-evaluated this build. The entry will need to
   * be re-evaluated if it is not done, but also if it was not completely evaluated last build and
   * this build is keepGoing.
   */
  private boolean isDoneForBuild(@Nullable NodeEntry entry) {
    return entry != null && entry.isDone();
  }
}
