/*
 * Copyright 2014-2023 TNG Technology Consulting GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tngtech.archunit.core.importer;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.tngtech.archunit.core.domain.JavaClassDescriptor;
import com.tngtech.archunit.core.importer.DomainBuilders.TryCatchBlockBuilder;
import org.objectweb.asm.Label;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

class TryCatchRecorder {
    private static final Logger log = LoggerFactory.getLogger(TryCatchRecorder.class);

    private final TryCatchBlocksFinishedListener tryCatchBlocksFinishedListener;
    private final Map<Label, Map<Label, RawTryCatchBlock>> blocksByEndByStart = new HashMap<>();
    private final Multimap<Label, RawTryCatchBlock> activeBlocksByEnd = HashMultimap.create();
    private final Set<Label> handlers = new HashSet<>();
    private Label lastEncounteredLabelWithoutLineNumber = null;
    private boolean active = false;

    TryCatchRecorder(TryCatchBlocksFinishedListener tryCatchBlocksFinishedListener) {
        this.tryCatchBlocksFinishedListener = tryCatchBlocksFinishedListener;
    }

    void onEncounteredLabel(Label label, int lineNumber) {
        if (!active) {
            return;
        }

        processStartingBlocks(label, lineNumber);
        processEndingBlocks(label);
        lastEncounteredLabelWithoutLineNumber = null;
    }

    /**
     * Most labels we will encounter twice, once without line number passed into {@link #onEncounteredLabel(Label)}
     * followed by a second time with line number passed into {@link #onEncounteredLabel(Label, int)}.<br>
     * Some labels will not have an associated line number, either because<br>
     * a) they mark the start of a synthetic try-catch-block, or<br>
     * b) they mark the end of a try-catch-block that ends in a return statement from within the try-block<br>
     * This means we never want to start a try-catch-block for a label without a line number, but we need to ensure
     * that try-catch-blocks can still transition from active to finished in this case.
     */
    void onEncounteredLabel(Label label) {
        if (!active) {
            return;
        }

        if (lastEncounteredLabelWithoutLineNumber != null) {
            processLabelWithoutLineNumber(lastEncounteredLabelWithoutLineNumber);
        }
        lastEncounteredLabelWithoutLineNumber = label;
    }

    private void processLabelWithoutLineNumber(Label label) {
        // normal try-catch-blocks always have a line number associated, so this is a synthetic one which we want to ignore
        blocksByEndByStart.remove(label);

        processEndingBlocks(label);
    }

    private void processStartingBlocks(Label start, int lineNumber) {
        Map<Label, RawTryCatchBlock> blocksByEnd = blocksByEndByStart.remove(start);
        if (blocksByEnd == null) {
            return;
        }

        for (Map.Entry<Label, RawTryCatchBlock> endToBlock : blocksByEnd.entrySet()) {
            endToBlock.getValue().setLineNumber(lineNumber);
            activeBlocksByEnd.put(endToBlock.getKey(), endToBlock.getValue());
        }
    }

    private void processEndingBlocks(Label end) {
        Collection<RawTryCatchBlock> finishedBlocks = activeBlocksByEnd.removeAll(end);

        Set<TryCatchBlockBuilder> finishedBuilders = finishedBlocks.stream()
                .map(block -> new TryCatchBlockBuilder()
                        .withCaughtThrowables(block.caughtThrowables)
                        .withLineNumber(block.lineNumber)
                        .withRawAccessesInTryBlock(block.accessesInTryBlock))
                .collect(toImmutableSet());
        tryCatchBlocksFinishedListener.onTryCatchBlocksFinished(finishedBuilders);

        if (blocksByEndByStart.isEmpty() && activeBlocksByEnd.isEmpty()) {
            active = false;
        }
    }

    void registerTryCatchBlock(Label start, Label end, Label handler, JavaClassDescriptor throwableType) {
        getOrCreateTryCatchBlock(start, end).addThrowable(throwableType);
        active = true;
        handlers.add(handler);
    }

    void registerTryFinallyBlock(Label start, Label end, Label handler) {
        if (!handlers.contains(start)) {
            // if the start is a handler, then it is the (additional, synthetic) finally block for the catch-block
            // we can ignore this, since there will already be a recorded try-finally block starting from try
            getOrCreateTryCatchBlock(start, end);
            active = true;
        }
        handlers.add(handler);
    }

    void registerAccess(RawAccessRecord accessRecord) {
        if (!active) {
            return;
        }
        activeBlocksByEnd.values().forEach(block -> block.addAccess(accessRecord));
    }

    void onEncounteredMethodEnd() {
        if (lastEncounteredLabelWithoutLineNumber != null) {
            processLabelWithoutLineNumber(lastEncounteredLabelWithoutLineNumber);
            lastEncounteredLabelWithoutLineNumber = null;
        }
        handlers.clear();

        if (!blocksByEndByStart.isEmpty()) {
            log.warn("Failed to process some try-catch-blocks");
            blocksByEndByStart.clear();
        }
        if (!activeBlocksByEnd.isEmpty()) {
            log.warn("Failed to finish processing of some active try-catch-blocks");
            activeBlocksByEnd.clear();
        }
    }

    private RawTryCatchBlock getOrCreateTryCatchBlock(Label start, Label end) {
        Map<Label, RawTryCatchBlock> blocksByEnd = blocksByEndByStart.computeIfAbsent(start, __ -> new HashMap<>());
        return blocksByEnd.computeIfAbsent(end, __ -> new RawTryCatchBlock());
    }

    private static class RawTryCatchBlock {
        private final Set<JavaClassDescriptor> caughtThrowables = new HashSet<>();
        private int lineNumber;
        private final Set<RawAccessRecord> accessesInTryBlock = new HashSet<>();

        void addThrowable(JavaClassDescriptor throwableType) {
            caughtThrowables.add(throwableType);
        }

        void setLineNumber(int lineNumber) {
            this.lineNumber = lineNumber;
        }

        void addAccess(RawAccessRecord accessRecord) {
            accessesInTryBlock.add(accessRecord);
        }
    }

    interface TryCatchBlocksFinishedListener {
        void onTryCatchBlocksFinished(Set<TryCatchBlockBuilder> tryCatchBlocks);
    }
}
