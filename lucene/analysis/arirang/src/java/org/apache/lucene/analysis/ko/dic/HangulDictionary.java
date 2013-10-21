package org.apache.lucene.analysis.ko.dic;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.util.fst.FST;
import org.apache.lucene.util.fst.FST.BytesReader;

class HangulDictionary {
  final FST<Byte> fst;
  final byte[] metadata;
  
  static final int RECORD_SIZE = 15;
  
  static final int SBASE = 0xAC00;
  static final int HANGUL_B0 = 0xE0 | (SBASE >> 12);
  static final int VCOUNT = 21;
  static final int TCOUNT = 28;
  static final int NCOUNT = VCOUNT * TCOUNT;
  
  public HangulDictionary(FST<Byte> fst, byte[] metadata) {
    this.fst = fst;
    this.metadata = metadata;
  }
  
  /** looks up word class for a word (exact match) */
  Byte lookup(String key) {
    // TODO: why is does this thing lookup empty strings?
    if (key.length() == 0) {
      return null;
    }
    final FST.Arc<Byte> arc = fst.getFirstArc(new FST.Arc<Byte>());

    final BytesReader fstReader = fst.getBytesReader();

    // Accumulate output as we go
    Byte output = fst.outputs.getNoOutput();
    for (int i = 0; i < key.length(); i++) {
      try {
        char ch = key.charAt(i);
        if (ch < 0xFF) {
          // latin-1: remap to hangul syllable
          if (fst.findTargetArc(HANGUL_B0, arc, arc, fstReader) == null) {
            return null;
          }
          output = fst.outputs.add(output, arc.output);
          if (fst.findTargetArc(0x80 | ((ch >> 6) & 0x3F), arc, arc, fstReader) == null) {
            return null;
          }
          output = fst.outputs.add(output, arc.output);
          if (fst.findTargetArc(0x80 | (ch & 0x3F), arc, arc, fstReader) == null) {
            return null;
          }
          output = fst.outputs.add(output, arc.output);
        } else if (ch >= SBASE && ch <= 0xD7AF) {
          // hangul syllable: decompose to jamo and remap to latin-1
          ch -= SBASE;
          if (fst.findTargetArc(ch / NCOUNT, arc, arc, fstReader) == null) {
            return null;
          }
          output = fst.outputs.add(output, arc.output);
          if (fst.findTargetArc((ch % NCOUNT) / TCOUNT, arc, arc, fstReader) == null) {
            return null;
          }
          output = fst.outputs.add(output, arc.output);
          if (fst.findTargetArc(ch % TCOUNT, arc, arc, fstReader) == null) {
            return null;
          }
          output = fst.outputs.add(output, arc.output);
        } else {
          return null;
        }
      } catch (IOException bogus) {
        throw new RuntimeException();
      }
    }

    if (arc.isFinal()) {
      return fst.outputs.add(output, arc.nextFinalOutput);
    } else {
      return null;
    }
  }
  
  /** looks up features for word class */
  char getFlags(byte clazz) {
    int off = clazz * RECORD_SIZE;
    return (char)((metadata[off] << 8) | (metadata[off+1] & 0xff));
  }
  
  /** return list of compounds for key and word class.
   * this retrieves the splits for the class and applies them to the key */
  List<CompoundEntry> getCompounds(String word, byte clazz) {
    int off = clazz * RECORD_SIZE;
    int numSplits = metadata[off+2];
    assert numSplits > 0;
    List<CompoundEntry> compounds = new ArrayList<>(numSplits+1);
    int last = 0;
    for (int i = 0; i < numSplits; i++) {
      int split = metadata[off+3+i];
      compounds.add(new CompoundEntry(word.substring(last, split), true));
      last = split;
    }
    compounds.add(new CompoundEntry(word.substring(last), true));
    return compounds;
  }
  
  /** return list of compounds for key and word class.
   * this retrieves the decompounded data for this irregular class */
  List<CompoundEntry> getIrregularCompounds(byte clazz) {
    int off = clazz * RECORD_SIZE;
    int numChars = metadata[off+2];
    // TODO: more efficient
    List<CompoundEntry> compounds = new ArrayList<>();
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < numChars; i++) {
      int idx = off+3+(i<<1);
      char next = (char)((metadata[idx] << 8) | (metadata[idx+1] & 0xff));
      if (next == ',') {
        compounds.add(new CompoundEntry(sb.toString(), true));
        sb.setLength(0);
      } else {
        sb.append(next);
      }
    }
    compounds.add(new CompoundEntry(sb.toString(), true));
    return compounds;
  }
  
  /** walks the fst for prefix and returns true if it his no dead end */
  boolean hasPrefix(CharSequence key) {
    final FST.Arc<Byte> arc = fst.getFirstArc(new FST.Arc<Byte>());

    final BytesReader fstReader = fst.getBytesReader();

    for (int i = 0; i < key.length(); i++) {
      try {
        char ch = key.charAt(i);
        if (ch < 0xFF) {
          // latin-1: remap to hangul syllable
          if (fst.findTargetArc(HANGUL_B0, arc, arc, fstReader) == null) {
            return false;
          }
          if (fst.findTargetArc(0x80 | ((ch >> 6) & 0x3F), arc, arc, fstReader) == null) {
            return false;
          }
          if (fst.findTargetArc(0x80 | (ch & 0x3F), arc, arc, fstReader) == null) {
            return false;
          }
        } else if (ch >= SBASE && ch <= 0xD7AF) {
          // hangul syllable: decompose to jamo and remap to latin-1
          ch -= SBASE;
          if (fst.findTargetArc(ch / NCOUNT, arc, arc, fstReader) == null) {
            return false;
          }
          if (fst.findTargetArc((ch % NCOUNT) / TCOUNT, arc, arc, fstReader) == null) {
            return false;
          }
          if (fst.findTargetArc(ch % TCOUNT, arc, arc, fstReader) == null) {
            return false;
          }
        } else {
          return false;
        }
      } catch (IOException bogus) {
        throw new RuntimeException();
      }
    }
    return true;
  }
}