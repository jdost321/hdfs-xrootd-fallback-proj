#                                               -*- Autoconf -*-
# Process this file with autoconf to produce a configure script.

AC_PREREQ([2.63])
AC_INIT([hdfs-xrootd-healer], [1.0.0])
AC_CONFIG_SRCDIR([edu/ucsd/t2/hdfs/xrootd/healer/BlockHealer.java])

AC_ARG_VAR([HADOOP_HOME], [*REQUIRED* directory containing hadoop-common.jar])
AC_ARG_VAR([HADOOP_HDFS_HOME], [*REQUIRED* directory containing hadoop-hdfs.jar])
AC_ARG_VAR([HADOOP_LIBEXEC_DIR], [defaults to $(HADOOP_HOME}/libexec, used by healer])

# Checks for programs.
AC_PROG_INSTALL

AC_CHECK_PROG([HAVE_JAVAC], [javac], [yes]) 

if test "x$HAVE_JAVAC" = x; then
  AC_MSG_ERROR([cannot find javac, exiting])
fi

# Checks for libraries.
AC_MSG_CHECKING([if HADOOP_HOME is set])
if test "x$HADOOP_HOME" = x; then
  AC_MSG_RESULT(no)
  AC_MSG_ERROR([HADOOP_HOME is not defined, exiting])
fi
AC_MSG_RESULT(yes)
AC_CHECK_FILE([${HADOOP_HOME}/hadoop-common.jar], [],
  [AC_MSG_ERROR([cannot find \${HADOOP_HOME}/hadoop-common.jar, exiting])])

AC_MSG_CHECKING([if HADOOP_HDFS_HOME is set])
if test "x$HADOOP_HDFS_HOME" = x; then
  AC_MSG_RESULT(no)
  AC_MSG_ERROR([HADOOP_HDFS_HOME is not defined, exiting])
fi
AC_MSG_RESULT(yes)
AC_CHECK_FILE([${HADOOP_HDFS_HOME}/hadoop-hdfs.jar], [],
  [AC_MSG_ERROR([cannot find \${HADOOP_HDFS_HOME}/hadoop-hdfs.jar, exiting])])

AC_MSG_CHECKING([if HADOOP_HADOOP_LIBEXEC_DIR is set])
if test "x$HADOOP_LIBEXEC_DIR" = x; then
  AC_MSG_RESULT(no)
  HADOOP_LIBEXEC_DIR=${HADOOP_HOME}/libexec
else
  AC_MSG_RESULT(yes)
fi

AC_CONFIG_FILES([Makefile])
AC_OUTPUT
