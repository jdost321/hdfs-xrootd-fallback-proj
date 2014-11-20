#!/bin/bash

dirs=`hdfs getconf -confKey dfs.datanode.data.dir | tr ',' ' '`
script_name=square_pusher.sh

while [ $# -gt 0 ];do
  in_mfile="$1.meta"
  in_bfile=`echo $1 | sed 's/^\(blk_[^_]\+\).*/\1/'`
  shift
  out_name=$1
  out_mfile="$1.meta"
  out_bfile=`echo $1 | sed 's/^\(blk_[^_]\+\).*/\1/'`
  shift

  d=`find $dirs -name $in_mfile -printf %h -quit`
  if [ $? -ne 0 -o -z "$d" ];then
    echo "${script_name}: ${out_name}: tmp file $in_mfile not found"
    continue 
  fi

  if [ -e ${d}/${out_mfile} ];then
    echo "${script_name}: ${out_name}: $out_mfile already exists"
    continue
  fi

  mv ${d}/${in_mfile} ${d}/${out_mfile}
  if [ $? -ne 0 ];then
    echo "${script_name}: ${out_name}: could not rename $in_mfile to $out_mfile"
    continue
  fi

  mv ${d}/${in_bfile} ${d}/${out_bfile}
  if [ $? -ne 0 ];then
    echo "${script_name}: ${out_name}: could not rename $in_bfile to $out_bfile"
    # something is wrong, so just put meta file back
    mv ${d}/${out_mfile} ${d}/${in_mfile}
  fi
done
