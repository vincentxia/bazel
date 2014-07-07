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

#include <libproc.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include "blaze_exit_code.h"
#include "blaze_util.h"
#include "blaze_util_platform.h"
#include "util/strings.h"

namespace blaze {

using std::string;

void WarnFilesystemType(const string& output_base) {
  // TODO(bazel-team): Should check for NFS.
  // TODO(bazel-team): Should check for case insensitive file systems?
}

pid_t GetPeerProcessId(int socket) {
  pid_t pid = 0;
  socklen_t len = sizeof(pid_t);
  if (getsockopt(socket, SOL_LOCAL, LOCAL_PEERPID, &pid, &len) < 0) {
    pdie(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR,
         "can't get server pid from connection");
  }
  return pid;
}

string GetSelfPath() {
  char pathbuf[PROC_PIDPATHINFO_MAXSIZE] = {};
  int len = proc_pidpath(getpid(), pathbuf, sizeof(pathbuf));
  if (len == 0) {
    pdie(blaze_exit_code::INTERNAL_ERROR, "error calling proc_pidpath");
  }
  return string(pathbuf, len);
}

uint64 MonotonicClock() {
  struct timeval ts = {};
  if (gettimeofday(&ts, NULL) < 0) {
    pdie(blaze_exit_code::INTERNAL_ERROR, "error calling gettimeofday");
  }
  return ts.tv_sec * 1000000000LL + ts.tv_usec * 1000;
}

uint64 ProcessClock() {
  return MonotonicClock();
}

void SetScheduling(bool batch_cpu_scheduling, int io_nice_level) {
  // stubbed out so we can compile for Darwin.
}

string GetProcessCWD(int pid) {
  struct proc_vnodepathinfo info = {};
  if (proc_pidinfo(
          pid, PROC_PIDVNODEPATHINFO, 0, &info, sizeof(info)) != sizeof(info)) {
    return "";
  }
  return string(info.pvi_cdir.vip_path);
}

bool IsSharedLibrary(string filename) {
  return blaze_util::ends_with(filename, ".dylib");
}

}   // namespace blaze.