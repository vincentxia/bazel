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

package com.google.devtools.build.lib.rules.cpp;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.rules.cpp.LinkerInputs.LibraryToLink;
import com.google.devtools.build.lib.util.FileTypeSet;

/**
 * Utility types and methods for generating command lines for the linker, given
 * a CppLinkAction or LinkConfiguration.
 *
 * <p>The linker commands, e.g. "ar", may not be functional, i.e.
 * they may mutate the output file rather than overwriting it.
 * To avoid this, we need to delete the output file before invoking the
 * command.  But that is not done by this class; deleting the output
 * file is the responsibility of the classes derived from LinkStrategy.
 */
public abstract class Link {

  private Link() {} // uninstantiable

  /** The set of valid linker input files.  */
  public static final FileTypeSet VALID_LINKER_INPUTS = FileTypeSet.of(
      CppFileTypes.ARCHIVE, CppFileTypes.PIC_ARCHIVE,
      CppFileTypes.ALWAYS_LINK_LIBRARY, CppFileTypes.ALWAYS_LINK_PIC_LIBRARY,
      CppFileTypes.OBJECT_FILE, CppFileTypes.PIC_OBJECT_FILE,
      CppFileTypes.SHARED_LIBRARY, CppFileTypes.VERSIONED_SHARED_LIBRARY,
      CppFileTypes.INTERFACE_SHARED_LIBRARY);

  /**
   * These file are supposed to be added using {@code addLibrary()} calls to {@link CppLinkAction}
   * but will never be expanded to their constituent {@code .o} files. {@link CppLinkAction} checks
   * that these files are never added as non-libraries.
   */
  public static final FileTypeSet SHARED_LIBRARY_FILETYPES = FileTypeSet.of(
      CppFileTypes.SHARED_LIBRARY,
      CppFileTypes.VERSIONED_SHARED_LIBRARY,
      CppFileTypes.INTERFACE_SHARED_LIBRARY);

  /**
   * These need special handling when --thin_archive is true. {@link CppLinkAction} checks that
   * these files are never added as non-libraries.
   */
  public static final FileTypeSet ARCHIVE_LIBRARY_FILETYPES = FileTypeSet.of(
      CppFileTypes.ARCHIVE,
      CppFileTypes.PIC_ARCHIVE,
      CppFileTypes.ALWAYS_LINK_LIBRARY,
      CppFileTypes.ALWAYS_LINK_PIC_LIBRARY);

  public static final FileTypeSet ARCHIVE_FILETYPES = FileTypeSet.of(
      CppFileTypes.ARCHIVE,
      CppFileTypes.PIC_ARCHIVE);

  public static final FileTypeSet LINK_LIBRARY_FILETYPES = FileTypeSet.of(
      CppFileTypes.ALWAYS_LINK_LIBRARY,
      CppFileTypes.ALWAYS_LINK_PIC_LIBRARY);


  /** The set of object files */
  public static final FileTypeSet OBJECT_FILETYPES = FileTypeSet.of(
      CppFileTypes.OBJECT_FILE,
      CppFileTypes.PIC_OBJECT_FILE);

  /**
   * Prefix that is prepended to command line entries that refer to the output
   * of cc_fake_binary compile actions. This is a bad hack to signal to the code
   * in {@code CppLinkAction#executeFake(Executor, FileOutErr)} that it needs
   * special handling.
   */
  public static final String FAKE_OBJECT_PREFIX = "fake:";

  /**
   * Types of ELF files that can be created by the linker (.a, .so, .lo,
   * executable).
   */
  public enum LinkTargetType {
    /** A normal static archive. */
    STATIC_LIBRARY(".a", true),

    /** A static archive with .pic.o object files (compiled with -fPIC). */
    PIC_STATIC_LIBRARY(".pic.a", true),

    /** An interface dynamic library. */
    INTERFACE_DYNAMIC_LIBRARY(".ifso", false),

    /** A dynamic library. */
    DYNAMIC_LIBRARY(".so", false),

    /** A static archive without removal of unused object files. */
    ALWAYS_LINK_STATIC_LIBRARY(".lo", true),

    /** A PIC static archive without removal of unused object files. */
    ALWAYS_LINK_PIC_STATIC_LIBRARY(".pic.lo", true),

    /** An executable binary. */
    EXECUTABLE("", false);

    private final String extension;
    private final boolean staticLibraryLink;

    private LinkTargetType(String extension, boolean staticLibraryLink) {
      this.extension = extension;
      this.staticLibraryLink = staticLibraryLink;
    }

    public String getExtension() {
      return extension;
    }

    public boolean isStaticLibraryLink() {
      return staticLibraryLink;
    }
  }

  /**
   * The degree of "staticness" of symbol resolution during linking.
   */
  public enum LinkStaticness {
    FULLY_STATIC,       // Static binding of all symbols.
    MOSTLY_STATIC,      // Use dynamic binding only for symbols from glibc.
    DYNAMIC,            // Use dynamic binding wherever possible.
  }

  /**
   * Types of archive.
   */
  public enum ArchiveType {
    FAT,            // Regular archive that includes its members.
    THIN,           // Thin archive that just points to its members.
    START_END_LIB   // A --start-lib ... --end-lib group in the command line.
  }

  static boolean useStartEndLib(LinkerInput linkerInput, ArchiveType archiveType) {
    // TODO(bazel-team): Figure out if PicArchives are actually used. For it to be used, both
    // linkingStatically and linkShared must me true, we must be in opt mode and cpu has to be k8.
    return archiveType == ArchiveType.START_END_LIB
        && ARCHIVE_FILETYPES.matches(linkerInput.getArtifact().getFilename())
        && linkerInput.containsObjectFiles();
  }

  /**
   * Expands the archives in a collection of artifacts. If deps is true we include all
   * dependencies. If it is false, only what should be passed to the link command.
   */
  private static Iterable<LinkerInput> filterMembersForLink(Iterable<LibraryToLink> inputs,
      final boolean globalNeedWholeArchive, final ArchiveType archiveType, final boolean deps) {
    ImmutableList.Builder<LinkerInput> builder = ImmutableList.builder();

    for (LibraryToLink inputLibrary : inputs) {
      Artifact input = inputLibrary.getArtifact();
      String name = input.getFilename();

      // True if the linker might use the members of this file, i.e., if the file is a thin or
      // start_end_lib archive (aka static library). Also check if the library contains object files
      // - otherwise getObjectFiles returns null, which would lead to an NPE in simpleLinkerInputs.
      boolean needMembersForLink = archiveType != ArchiveType.FAT &&
          ARCHIVE_LIBRARY_FILETYPES.matches(name) && inputLibrary.containsObjectFiles();

      // True if we will pass the members instead of the original archive.
      boolean passMembersToLinkCmd = needMembersForLink &&
          (globalNeedWholeArchive || LINK_LIBRARY_FILETYPES.matches(name));

      // If deps is false (when computing the inputs to be passed on the command line), then it's an
      // if-then-else, i.e., the passMembersToLinkCmd flag decides whether to pass the object files
      // or the archive itself. This flag in turn is based on whether the archives are fat or not
      // (thin archives or start_end_lib) - we never expand fat archives, but we do expand non-fat
      // archives if we need whole-archives for the entire link, or for the specific library (i.e.,
      // if alwayslink=1).
      //
      // If deps is true (when computing the inputs to be passed to the action as inputs), then it
      // becomes more complicated. We always need to pass the members for thin and start_end_lib
      // archives (needMembersForLink). And we _also_ need to pass the archive file itself unless
      // it's a start_end_lib archive (unless it's an alwayslink library).

      if (passMembersToLinkCmd || (deps && needMembersForLink)) {
        builder.addAll(LinkerInputs.simpleLinkerInputs(inputLibrary.getObjectFiles()));
      }

      if (!(passMembersToLinkCmd || (deps && useStartEndLib(inputLibrary, archiveType)))) {
        builder.add(inputLibrary);
      }
    }

    return builder.build();
  }

  /**
   * Replace always used archives with its members. This is used to build the linker cmd line.
   */
  public static Iterable<LinkerInput> mergeInputsCmdLine(NestedSet<LibraryToLink> inputs,
      boolean globalNeedWholeArchive, ArchiveType archiveType) {
    return filterMembersForLink(inputs, globalNeedWholeArchive, archiveType, false);
  }

  /**
   * Add in any object files which are implicitly named as inputs by the linker.
   */
  public static Iterable<LinkerInput> mergeInputsDependencies(NestedSet<LibraryToLink> inputs,
      boolean globalNeedWholeArchive, ArchiveType archiveType) {
    return filterMembersForLink(inputs, globalNeedWholeArchive, archiveType, true);
  }
}
