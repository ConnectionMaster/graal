/*
 * Copyright (c) 2025, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.hosted.webimage.codegen.irwalk;

import java.util.List;

import com.oracle.svm.hosted.webimage.codegen.JSCodeGenTool;
import com.oracle.svm.hosted.webimage.codegen.reconstruction.ReconstructionData;
import com.oracle.svm.hosted.webimage.logging.LoggerContext;
import com.oracle.svm.hosted.webimage.metrickeys.MethodMetricKeys;
import com.oracle.svm.hosted.webimage.metrickeys.StackifierMetricKeys;
import com.oracle.svm.hosted.webimage.options.WebImageOptions;

import jdk.graal.compiler.core.common.cfg.BlockMap;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeMap;
import jdk.graal.compiler.nodes.cfg.ControlFlowGraph;
import jdk.graal.compiler.nodes.cfg.HIRBlock;

public final class WebImageJSIRWalker extends StackifierIRWalker {

    public WebImageJSIRWalker(JSCodeGenTool jsLTools, ControlFlowGraph cfg, BlockMap<List<Node>> blockToNodeMap, NodeMap<HIRBlock> nodeToBlock, ReconstructionData reconstructionData) {
        super(jsLTools, cfg, blockToNodeMap, nodeToBlock, reconstructionData);

    }

    @Override
    protected void lower(DebugContext debugContext) {
        LoggerContext.counter(StackifierMetricKeys.NUM_ELSE_SCOPES).add(stackifierData.getNrElseScopes());
        LoggerContext.counter(StackifierMetricKeys.NUM_THEN_SCOPES).add(stackifierData.getNrThenScopes());
        LoggerContext.counter(StackifierMetricKeys.NUM_LOOP_SCOPES).add(stackifierData.getNrLoopScopes());
        LoggerContext.counter(StackifierMetricKeys.NUM_FORWARD_BLOCKS).add(stackifierData.getNrOfLabeledBlocks());
        LoggerContext.counter(MethodMetricKeys.NUM_BLOCKS).add(stackifierData.getBlocks().length);
        super.lower(debugContext);
    }

    @Override
    protected boolean lowerNode(Node n) {
        boolean lowererd = super.lowerNode(n);
        if (!lowererd && WebImageOptions.genJSComments()) {
            String info = codeGenTool.nodeLowerer().nodeDebugInfo(n);
            codeGenTool.genComment("ignored " + info);
        }
        return lowererd;
    }

}
