/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License (the "License").
 * You may not use this file except in compliance with the License.
 *
 * See LICENSE.txt included in this distribution for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at LICENSE.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information: Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 */

/*
 * Copyright (c) 2005, 2019, Oracle and/or its affiliates. All rights reserved.
 */
package org.opengrok.indexer.web;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.TreeMap;

/**
 * An Extremely Fast Tagged Attribute Read-only File System.
 * Created on October 12, 2005
 *
 * A Eftar File has the following format
 * FILE --&gt; Record  ( Record | tagString ) *
 * Record --&gt; 64bit:Hash 16bit:childrenOffset  16bit:(numberChildren|lenthOfTag) 16bit:tagOffset
 *
 * It is a tree of tagged names,
 * doing binary search in sorted list of children
 *
 * @author Chandan
 */
public class EftarFile {

    public static final int RECORD_LENGTH = 14;
    private long offset;

    class Node {

        public long hash;
        public String tag;
        public Map<Long, Node> children;
        public long tagOffset;
        public long childOffset;
        public long myOffset;

        Node(long hash, String tag) {
            this.hash = hash;
            this.tag = tag;
            children = new TreeMap<>();
        }

        public Node put(long hash, String desc) {
            if (children.get(hash) == null) {
                children.put(hash, new Node(hash, desc));
            }
            return children.get(hash);
        }

        public Node get(long hash) {
            return children.get(hash);
        }
    }

    public static long myHash(String name) {
        if (name == null || name.length() == 0) {
            return 0;
        }
        long hash = 2861;
        int n = name.length();
        if (n > 100) {
            n = 100;
        }
        for (int i = 0; i < n; i++) {
            hash = (hash * 641 + name.charAt(i) * 2969 + hash << 6) % 9322397;
        }
        return hash;
    }

    private void write(Node n, DataOutputStream out) throws IOException {
        if (n.tag != null) {
            out.write(n.tag.getBytes());
            offset += n.tag.length();
        }
        for (Node childnode : n.children.values()) {
            out.writeLong(childnode.hash);
            if (childnode.children.size() > 0) {
                out.writeShort((short) (childnode.childOffset - offset));
                out.writeShort((short) childnode.children.size());
            } else {
                out.writeShort(0);
                if (childnode.tag == null) {
                    out.writeShort((short) 0);
                } else {
                    out.writeShort((short) childnode.tag.length());
                }
            }
            if (childnode.tag == null) {
                out.writeShort(0);
            } else {
                out.writeShort((short) (childnode.tagOffset - offset));
            }
            offset += RECORD_LENGTH;
        }
        for (Node childnode : n.children.values()) {
            write(childnode, out);
        }
    }

    private void traverse(Node n) {
        if (n.tag == null) {
            n.tagOffset = 0;
        } else {
            n.tagOffset = offset;
            offset += n.tag.length();
        }
        if (n.children.size() > 0) {
            n.childOffset = offset;
            offset += (RECORD_LENGTH * n.children.size());
        } else {
            n.childOffset = 0;
        }
        for (Node childnode : n.children.values()) {
            traverse(childnode);
        }
    }
    private Node root;

    public void readInput(File inputFile) throws IOException {
        try (BufferedReader r = new BufferedReader(new FileReader(inputFile))) {
            readInput(r);
        }
    }

    public void readInput(String input) throws IOException {
        try (BufferedReader r = new BufferedReader(new StringReader(input))) {
            readInput(r);
        }
    }

    /**
     * Reads the input into interim representation. Can be called multiple times.
     * @param r reader
     * @throws IOException
     */
    private void readInput(BufferedReader r) throws IOException {
        if (root == null) {
            root = new Node(1, null);
        }
        String line;
        while ((line = r.readLine()) != null) {
            int tab = line.indexOf('\t');
            if (tab > 0) {
                String path = line.substring(0, tab);
                String desc = line.substring(tab + 1);
                StringTokenizer toks = new StringTokenizer(path, "\\/");
                Node n = root;
                while (toks.hasMoreTokens()) {
                    n = n.put(myHash(toks.nextToken()), null);
                }
                n.tag = desc;
            }
        }
    }

    public void write(String outPath) throws FileNotFoundException, IOException {
        offset = RECORD_LENGTH;
        traverse(root);
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(outPath)))) {
            out.writeLong(0x5e33);
            out.writeShort(RECORD_LENGTH);
            out.writeShort(root.children.size());
            out.writeShort(0);
            offset = RECORD_LENGTH;
            write(root, out);
        }
    }

    public void create(File inputFile, String outputPath) throws IOException {
        readInput(inputFile);
        write(outputPath);
    }

    public void create(String input, String outputPath) throws IOException, FileNotFoundException {
        readInput(input);
        write(outputPath);
    }
}
