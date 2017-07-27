#!/bin/bash
# Copyright (c) YugaByte, Inc.

# NOTE:
# This script contains the steps needed to run yugaware driven integration test.
# It is present on scheduler machine at /home/centos/scripts/itest_cron.sh and is part of cron task to run daily as shown below
# This is a reference replica.
#
# The way to enable it as a cron job is to add the following three lines via `crontab -e`:
# PATH=/home/centos/code/devtools/bin:/home/centos/code/google-styleguide/cpplint:/home/centos/tools/google-cloud-sdk/bin:/home/centos/.local/bin:/home/centos/.linuxbrew-yb-build/bin:/home/centos/tools/arcanist/bin:/usr/local/bin:/opt/yugabyte/yb-server/bin:/opt/yugabyte/yugaware/bin:/usr/lib64/ccache:/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/opt/apache-maven-3.3.9/bin:/home/centos/.local/bin:/home/centos/bin
# DEVOPS_HOME=/home/centos/code/devops
# 22 22 * * * /home/centos/scripts/itest_cron.sh >> /var/log/itest.log 2>&1

set -euo pipefail

code_root=/home/centos/code/
itest_yw_repo="$code_root"/yugaware
itest_devops_repo="$code_root"/devops

if [ ! -d "$itest_yw_repo" ]; then
  cd $code_root
  git clone git@bitbucket.org:yugabyte/yugaware.git
fi

if [ ! -d "$itest_devops_repo" ]; then
  cd $code_root
  git clone git@bitbucket.org:yugabyte/devops.git
fi

export DEVOPS_HOME=$itest_devops_repo

cd $itest_devops_repo
git stash
git checkout master
git pull --rebase
cd bin
./install_python_requirements.sh

cd $itest_yw_repo
git stash
git checkout master
git pull --rebase

# The `unset` is needed to make yugabyte build correctly (otherwise hit ELF lib check failures).
unset LD_LIBRARY_PATH; "$itest_yw_repo"/run_itest --perform_edits --perform_perf_runs --notify
