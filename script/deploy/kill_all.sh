#!/bin/bash
SHELL_TYPE=`readlink /proc/$$/exe | tr '/' '\n' | tail -1`
if [ "$SHELL_TYPE" != "bash" ] ; then
  echo "Script must be executed in bash. Exiting..."
  exit
fi


#############
# Functions #
#############

######################################################
# Check if all neccessary programs are installed
# Globals:
# Arguments:
#   node_file - The configuration file
######################################################
check_programs() {
  local node_file=$1

  if ! hash cat 2>/dev/null ; then
    echo "Please install coreutils. Used for cat, cut and readlink. Exiting..."
    exit
  fi
  if ! hash grep 2>/dev/null ; then
    echo "Please install grep. Exiting..."
    exit
  fi
  if ! hash sed 2>/dev/null ; then
    echo "Please install sed. Exiting..."
    exit
  fi
  if ! hash hostname 2>/dev/null ; then
    echo "Please install hostname. Exiting..."
    exit
  fi
  if ! hash pkill 2>/dev/null ; then
    echo "Please install procps. Used for pkill. Exiting..."
    exit
  fi
  if ! hash host 2>/dev/null ; then
    echo "Please install bind9-host. Used for host. Exiting..."
    exit
  fi
  if ! hash getent 2>/dev/null ; then
    echo "Please install libc-bin. Used for getent. Exiting..."
    exit
  fi
  if ! hash ssh 2>/dev/null ; then
    echo "Please install openssh-client. Used for scp and ssh. Exiting..."
    exit
  fi
}

######################################################
# Resolve hostname to IP
# Globals:
# Arguments:
#   hostname - The hostname to resolve
# Return:
#   ip - The IP address
######################################################
resolve() {
  local hostname=$1
  local ip=""

  ip=`host $hostname | cut -d ' ' -f 4 | grep -E "[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}"`
  if [ "$ip" = "" ] ; then
    ip=`getent hosts $hostname | cut -d ' ' -f 1 | grep -E "[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}"`
    if [ "$ip" = "" ] ; then
      echo "ERROR: $hostname could not be identified. Seting default 127.0.0.1"
    fi
  fi
  echo "$ip"
}

######################################################
# Close all instances
# Globals:
#   NODES
#   LOCALHOST
#	THIS_HOST
# Arguments:
#   None
######################################################
close() {
  echo "Closing all dxram instances..."
  local node=""
  while read node || [[ -n "$node" ]]; do
    local hostname=`echo $node | cut -d ',' -f 1`
    local role=`echo $node | cut -d ',' -f 2`
	local ip=`resolve $hostname`

    if [ "$role" = "Z" ] ; then
      # Stop ZooKeeper?
      echo "ZooKeeper might stay alive"
    else
      if [ "$ip" = "$LOCALHOST" -o "$ip" = "$THIS_HOST" ] ; then
        pkill -9 -f DXRAM.jar
      else
        ssh $hostname -n "pkill -9 -f DXRAM.jar"
      fi
    fi
  done <<< "$NODES"

  echo "Exiting..."
  exit
}


###############
# Entry point #
###############

if [ "$1" = "" ] ; then
  echo "Missing parameter: Configuration file"
  echo "  Example: ./kill_all.sh SimpleTest.conf"
  exit
fi

node_file="./$1"
if [ "${node_file: -5}" != ".conf" ] ; then
  node_file="${node_file}.conf"
fi

check_programs "$node_file"

# Trim node file
NODES=`cat "$node_file" | grep -v '#' | sed 's/, /,/g' | sed 's/,\t/,/g'`
NODES=`echo "$NODES" | grep -v 'DXRAM_PATH'`
NODES=`echo "$NODES" | grep -v 'ZOOKEEPER_PATH'`

# Set default values
readonly LOCALHOST=`resolve "localhost"`
if [ `echo $LOCALHOST | cut -d "." -f 1` != "127" ] ; then
	echo "Illegal loopback device (ip: $LOCALHOST). Exiting..."
	exit
fi
readonly THIS_HOST=`resolve $(hostname)`

echo "########################################"
echo "Killing all DXRAM instances of $(echo $1 | cut -d '.' -f 1)"
echo "########################################"
echo -e "\n\n"

close