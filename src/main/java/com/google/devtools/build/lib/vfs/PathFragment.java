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
package com.google.devtools.build.lib.vfs;

import com.google.common.base.Function;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.util.StringCanonicalizer;

import java.io.File;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Set;

/**
 * This class represents an immutable UNIX filesystem path, which may be absolute or relative. The
 * path is maintained as a simple ordered list of path segment strings.
 *
 * <p>This class is independent from other VFS classes, especially anything requiring native code.
 * It is safe to use in places that need simple segmented string path functionality.
 */
@Immutable @ThreadSafe
public final class PathFragment implements Comparable<PathFragment>, Serializable {

  public static final int INVALID_SEGMENT = -1;

  public static final char SEPARATOR_CHAR = '/';
  public static final String ROOT_DIR = "/";

  /** An empty path fragment. */
  public static final PathFragment EMPTY_FRAGMENT = new PathFragment("");

  public static final Function<String, PathFragment> TO_PATH_FRAGMENT =
      new Function<String, PathFragment>() {
        @Override
        public PathFragment apply(String str) {
          return new PathFragment(str);
        }
      };

  private final String[] segments;
  private final boolean isAbsolute;
  // hashCode and path are lazily initialized but semantically immutable.
  private int hashCode;
  private String path;

  /**
   * Construct a PathFragment from a string, which is an absolute or relative UNIX path.
   */
  public PathFragment(String path) {
    this.isAbsolute = path.startsWith(ROOT_DIR);
    this.segments = segment(path, this.isAbsolute ? ROOT_DIR.length() : 0);
  }

  /**
   * Construct a PathFragment from a java.io.File, which is an absolute or
   * relative UNIX path.  Does not support Windows-style Files.
   */
  public PathFragment(File path) {
    this(path.getPath());
  }

  /**
   * Constructs a PathFragment, taking ownership of segments. Package-private,
   * because it does not perform a defensive clone of the segments array. Used
   * here in PathFragment, and by Path.asFragment() and Path.relativeTo().
   */
  PathFragment(boolean isAbsolute, String[] segments) {
    this.segments = segments;
    this.isAbsolute = isAbsolute;
  }

  /**
   * Construct a PathFragment from a sequence of other PathFragments. The new
   * fragment will be absolute iff the first fragment was absolute.
   */
  public PathFragment(PathFragment first, PathFragment second, PathFragment... more) {
    // TODO(bazel-team): The handling of absolute path fragments in this constructor is unexpected.
    this.segments = new String[sumLengths(first, second, more)];
    int offset = 0;
    offset += addSegments(offset, first);
    offset += addSegments(offset, second);
    for (PathFragment fragment : more) {
      offset += addSegments(offset, fragment);
    }
    this.isAbsolute = first.isAbsolute();
  }

  private int addSegments(int offset, PathFragment fragment) {
    int count = fragment.segmentCount();
    System.arraycopy(fragment.segments, 0, this.segments, offset, count);
    return count;
  }

  private static int sumLengths(PathFragment first, PathFragment second, PathFragment[] more) {
    int total = first.segmentCount() + second.segmentCount();
    for (PathFragment fragment : more) {
      total += fragment.segmentCount();
    }
    return total;
  }

  /**
   * Segments the string passed in as argument and returns an array of strings.
   * The split is performed along occurrences of (sequences of) the slash
   * character.
   *
   * @param toSegment the string to segment
   * @param offset how many characters from the start of the string to ignore.
   */
  private static String[] segment(String toSegment, int offset) {
    char[] chars = toSegment.toCharArray();
    int length = chars.length;

    // Handle "/" and "" quickly.
    if (length == offset) {
      return new String[0];
    }

    // We make two passes through the array of characters: count & alloc,
    // because simply using ArrayList was a bottleneck showing up during profiling.
    int seg = 0;
    int start = offset;
    for (int i = offset; i < length; i++) {
      if (chars[i] == SEPARATOR_CHAR) {
        if (i > start) {  // to skip repeated separators
          seg++;
        }
        start = i + 1;
      }
    }
    if (start < length) {
      seg++;
    }
    String[] result = new String[seg];
    seg = 0;
    start = offset;
    for (int i = offset; i < length; i++) {
      if (chars[i] == SEPARATOR_CHAR) {
        if (i > start) {  // to skip repeated separators
          // Make a copy of the String here to allow the interning to save memory. String.substring
          // does not make a copy, but refers to the original char array, preventing garbage
          // collection of the parts that are unnecessary.
          result[seg] = StringCanonicalizer.intern(new String(chars, start,  i - start));
          seg++;
        }
        start = i + 1;
      }
    }
    if (start < length) {
      result[seg] = StringCanonicalizer.intern(new String(chars, start, length - start));
      seg++;
    }
    return result;
  }

  private Object writeReplace() {
    return new PathFragmentSerializationProxy(toString());
  }

  private void readObject(ObjectInputStream stream) throws InvalidObjectException {
    throw new InvalidObjectException("Serialization is allowed only by proxy");
  }

  /**
   * Returns the path string using '/' as the name-separator character.  Returns "" if the path
   * is both relative and empty.
   */
  public String getPathString() {
    // Double-checked locking works, even without volatile, because path is a String, according to:
    // http://www.cs.umd.edu/~pugh/java/memoryModel/DoubleCheckedLocking.html
    if (path == null) {
      synchronized (this) {
        if (path == null) {
          path = StringCanonicalizer.intern(joinSegments(SEPARATOR_CHAR));
        }
      }
    }
    return path;
  }

  /**
   * Returns "." if the path fragment is both relative and empty, or {@link
   * #getPathString} otherwise.
   */
  // TODO(bazel-team): Change getPathString to do this - this behavior makes more sense.
  public String getSafePathString() {
    return (!isAbsolute && (segmentCount() == 0)) ? "." : getPathString();
  }

  private String joinSegments(char separatorChar) {
    if (segments.length == 0 && isAbsolute) {
      return ROOT_DIR;
    }

    // Profile driven optimization:
    // Preallocate a size determined by the number of segments, so that
    // we do not have to expand the capacity of the StringBuilder.
    // Heuristically, this estimate is right for about 99% of the time.
    int estimateSize = segments.length == 0 ? 0 : (segments.length + 1) * 20;
    StringBuilder result = new StringBuilder(estimateSize);

    for (String segment : segments) {
      if (result.length() > 0 || isAbsolute) {
        result.append(separatorChar);
      }
      result.append(segment);
    }
    return result.toString();
  }

  /**
   * Return true iff none of the segments are either "." or "..".
   */
  public boolean isNormalized() {
    for (String segment : segments) {
      if (segment.equals(".") || segment.equals("..")) {
        return false;
      }
    }
    return true;
  }

  /**
   * Normalizes the path fragment: removes "." and ".." segments if possible
   * (if there are too many ".." segments, the resulting PathFragment will still
   * start with "..").
   */
  public PathFragment normalize() {
    String[] scratchSegments = new String[segments.length];
    int segmentCount = 0;

    for (String segment : segments) {
      if (segment.equals(".")) {
        // Just discard it
      } else if (segment.equals("..")) {
        if (segmentCount > 0 && !scratchSegments[segmentCount - 1].equals("..")) {
          // Remove the last segment, if there is one and it is not "..". This
          // means that the resulting PathFragment can still contain ".."
          // segments at the beginning.
            segmentCount--;
        } else {
          scratchSegments[segmentCount++] = segment;
        }
      } else {
        scratchSegments[segmentCount++] = segment;
      }
    }

    if (segmentCount == segments.length) {
      // Optimization, no new PathFragment needs to be created.
      return this;
    }

    return new PathFragment(isAbsolute, subarray(scratchSegments, 0, segmentCount));
  }

  /**
   * Returns the path formed by appending the relative or absolute path fragment
   * {@code suffix} to this path.
   *
   * <p>If suffix is absolute, the current path will be ignored; otherwise, they
   * will be concatenated. This is a purely syntactic operation, with no path
   * normalization or I/O performed.
   */
  public PathFragment getRelative(PathFragment otherFragment) {
    return otherFragment.isAbsolute()
        ? otherFragment
        : new PathFragment(this, otherFragment);
  }

  /**
   * Returns the path formed by appending the relative or absolute string
   * {@code path} to this path.
   *
   * <p>If the given path string is absolute, the current path will be ignored;
   * otherwise, they will be concatenated. This is a purely syntactic operation,
   * with no path normalization or I/O performed.
   */
  public PathFragment getRelative(String path) {
    return getRelative(new PathFragment(path));
  }

  /**
   * Returns the path formed by appending the single non-special segment
   * "baseName" to this path.
   *
   * <p>You should almost always use {@link #getRelative} instead, which has
   * the same performance characteristics if the given name is a valid base
   * name, and which also works for '.', '..', and strings containing '/'.
   *
   * @throws IllegalArgumentException if {@code baseName} is not a valid base
   *     name according to {@link FileSystemUtils#checkBaseName}
   */
  public PathFragment getChild(String baseName) {
    FileSystemUtils.checkBaseName(baseName);
    baseName = StringCanonicalizer.intern(baseName);
    String[] newSegments = new String[segments.length + 1];
    System.arraycopy(segments, 0, newSegments, 0, segments.length);
    newSegments[newSegments.length - 1] = baseName;
    return new PathFragment(isAbsolute, newSegments);
  }

  /**
   * Returns the last segment of this path, or "" for the empty fragment.
   */
  public String getBaseName() {
    return (segments.length == 0) ? "" : segments[segments.length - 1];
  }

  /**
   * Returns a relative path fragment to this path, relative to
   * {@code ancestorDirectory}.
   * <p>
   * <code>x.relativeTo(z) == y</code> implies
   * <code>z.getRelative(y) == x</code>.
   * <p>
   * For example, <code>"foo/bar/wiz".relativeTo("foo")</code>
   * returns <code>"bar/wiz"</code>.
   */
  public PathFragment relativeTo(PathFragment ancestorDirectory) {
    String[] ancestorSegments = ancestorDirectory.segments();
    int ancestorLength = ancestorSegments.length;

    if (isAbsolute != ancestorDirectory.isAbsolute() ||
        segments.length < ancestorLength) {
      throw new IllegalArgumentException("PathFragment " + this
          + " is not beneath " + ancestorDirectory);
    }

    for (int index = 0; index < ancestorLength; index++) {
      if (!segments[index].equals(ancestorSegments[index])) {
        throw new IllegalArgumentException("PathFragment " + this
            + " is not beneath " + ancestorDirectory);
      }
    }

    int length = segments.length - ancestorLength;
    String[] resultSegments = subarray(segments, ancestorLength, length);
    return new PathFragment(false, resultSegments);
  }

  /**
   * Returns a relative path fragment to this path, relative to {@code path}.
   */
  public PathFragment relativeTo(String path) {
    return relativeTo(new PathFragment(path));
  }

  /**
   * Returns a new PathFragment formed by appending {@code newName} to the
   * parent directory. Null is returned iff this method is called on a
   * PathFragment with zero segments.  If {@code newName} designates an absolute path,
   * the value of {@code this} will be ignored and a PathFragment corresponding to
   * {@code newName} will be returned.  This behavior is consistent with the behavior of
   * {@link #getRelative(String)}.
   */
  public PathFragment replaceName(String newName) {
    return (segments.length == 0)
        ? null
        : getParentDirectory().getRelative(newName);
  }

  /**
   * Returns a path representing the parent directory of this path,
   * or null iff this Path represents the root of the filesystem.
   *
   * <p>Note: This method DOES NOT normalize ".."  and "." path segments.
   */
  public PathFragment getParentDirectory() {
    return (segments.length == 0) ? null : subFragment(0, segments.length - 1);
  }

  /**
   * Returns true iff {@code prefix}, considered as a list of path segments, is
   * a prefix of {@code this}, and that they are both relative or both
   * absolute.
   *
   * This is a reflexive, transitive, anti-symmetric relation (i.e. a partial
   * order)
   */
  public boolean startsWith(PathFragment prefix) {
    if (this.isAbsolute != prefix.isAbsolute ||
        this.segments.length < prefix.segments.length) {
      return false;
    }
    for (int i = 0, len = prefix.segments.length; i < len; i++) {
      if (!this.segments[i].equals(prefix.segments[i])) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns true iff {@code suffix}, considered as a list of path segments, is
   * relative and a suffix of {@code this}, or both are absolute and equal.
   *
   * This is a reflexive, transitive, anti-symmetric relation (i.e. a partial
   * order)
   */
  public boolean endsWith(PathFragment suffix) {
    if ((suffix.isAbsolute && !suffix.equals(this)) ||
        this.segments.length < suffix.segments.length) {
      return false;
    }
    int offset = this.segments.length - suffix.segments.length;
    for (int i = 0; i < suffix.segments.length; i++) {
      if (!this.segments[offset + i].equals(suffix.segments[i])) {
        return false;
      }
    }
    return true;
  }

  private static String[] subarray(String[] array, int start, int length) {
    String[] subarray = new String[length];
    System.arraycopy(array, start, subarray, 0, length);
    return subarray;
  }

  /**
   * Returns a new path fragment that is a sub fragment of this one.
   * The sub fragment begins at the specified <code>beginIndex</code> segment
   * and ends at the segment at index <code>endIndex - 1</code>. Thus the number
   * of segments in the new PathFragment is <code>endIndex - beginIndex</code>.
   *
   * @param      beginIndex   the beginning index, inclusive.
   * @param      endIndex     the ending index, exclusive.
   * @return     the specified sub fragment, never null.
   * @exception  IndexOutOfBoundsException  if the
   *             <code>beginIndex</code> is negative, or
   *             <code>endIndex</code> is larger than the length of
   *             this <code>String</code> object, or
   *             <code>beginIndex</code> is larger than
   *             <code>endIndex</code>.
   */
  public PathFragment subFragment(int beginIndex, int endIndex) {
    int count = segments.length;
    if ((beginIndex < 0) || (beginIndex > endIndex) || (endIndex > count)) {
      throw new IndexOutOfBoundsException();
    }
    boolean isAbsolute = (beginIndex == 0) && this.isAbsolute;
    return ((beginIndex == 0) && (endIndex == count)) ? this :
        new PathFragment(isAbsolute,
                         subarray(segments, beginIndex, endIndex - beginIndex));
  }

  /**
   * Returns true iff the path represented by this object is absolute.
   */
  public boolean isAbsolute() {
    return isAbsolute;
  }

  /**
   * Returns the segments of this path fragment. This array should not be
   * modified.
   */
  String[] segments() {
    return segments;
  }

  /**
   * Returns the number of segments in this path.
   */
  public int segmentCount() {
    return segments.length;
  }

  /**
   * Returns the specified segment of this path; index must be positive and
   * less than numSegments().
   */
  public String getSegment(int index) {
    return segments[index];
  }

  /**
   * Returns the index of the first segment which equals one of the input values
   * or {@link PathFragment#INVALID_SEGMENT} if none of the segments match.
   */
  public int getFirstSegment(Set<String> values) {
    for (int i = 0; i < segments.length; i++) {
      if (values.contains(segments[i])) {
        return i;
      }
    }
    return INVALID_SEGMENT;
  }

  /**
   * Returns true iff this path contains uplevel references "..".
   */
  public boolean containsUplevelReferences() {
    for (String segment : segments) {
      if (segment.equals("..")) {
        return true;
      }
    }
    return false;
  }

  @Override
  public int hashCode() {
    int h = hashCode;
    if (h == 0) {
      h = isAbsolute ? 1 : 0;
      for (String segment : segments) {
        h = h * 31 + segment.hashCode();
      }
      hashCode = h;
    }
    return h;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof PathFragment)) {
      return false;
    }
    PathFragment otherPath = (PathFragment) other;
    return isAbsolute == otherPath.isAbsolute &&
        Arrays.equals(otherPath.segments, segments);
  }

  /**
   * Compares two PathFragments using the lexicographical order.
   */
  @Override
  public int compareTo(PathFragment p2) {
    if (isAbsolute != p2.isAbsolute) {
      return isAbsolute ? -1 : 1;
    }
    PathFragment p1 = this;
    String[] segments1 = p1.segments;
    String[] segments2 = p2.segments;
    int len1 = segments1.length;
    int len2 = segments2.length;
    int n = Math.min(len1, len2);
    for (int i = 0; i < n; i++) {
      String segment1 = segments1[i];
      String segment2 = segments2[i];
      if (!segment1.equals(segment2)) {
       return segment1.compareTo(segment2);
      }
    }
    return len1 - len2;
  }

  @Override
  public String toString() {
    return getPathString();
  }
}