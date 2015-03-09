#                                               -*- Autoconf -*-
# Process this file with autoconf to produce a configure script.

AC_PREREQ([2.63])
AC_INIT([hdfs-xrootd-fallback], [1.0.1])
AC_CONFIG_SRCDIR([src/XrdBlockFetcher.cxx])

AC_ARG_VAR([JAVA_HOME], [*REQUIRED*])
AC_ARG_VAR([HADOOP_HOME], [*REQUIRED* directory containing hadoop-common.jar])
AC_ARG_VAR([HADOOP_HDFS_HOME], [*REQUIRED* directory containing hadoop-hdfs.jar])
AC_ARG_VAR([HADOOP_CLIENT], [defaults to $(HADOOP_HOME}/client, fallback jar installs here])

# Checks for programs.
AC_PROG_CXX
AC_PROG_INSTALL
AC_PROG_LN_S

AC_CHECK_PROG([HAVE_JAVAC], [javac], [yes]) 

if test "x$HAVE_JAVAC" = x; then
  AC_MSG_ERROR([cannot find javac, exiting])
fi

AC_MSG_CHECKING([if JAVA_HOME is set])
if test "x$JAVA_HOME" = x; then
  AC_MSG_RESULT(no)
  AC_MSG_ERROR([JAVA_HOME is not defined, exiting])
fi
AC_MSG_RESULT(yes)

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

AC_MSG_CHECKING([if HADOOP_CLIENT is set])
if test "x$HADOOP_CLIENT" = x; then
  AC_MSG_RESULT(no)
  HADOOP_CLIENT=${HADOOP_HOME}/client
else
  AC_MSG_RESULT(yes)
fi

# Checks for header files.
AC_CHECK_FILE([${JAVA_HOME}/include/jni.h], [],
  [AC_MSG_ERROR([cannot find \${JAVA_HOME}/include/jni.h, exiting])])

AC_LANG([C++])
AC_CHECK_HEADERS([pcrecpp.h], [],
  [AC_MSG_ERROR([cannot find pcrecpp.h, exiting])])
AC_CHECK_HEADERS([XrdClient/XrdClient.hh], [],
  [AC_MSG_ERROR([cannot find XrdClient/XrdClient.hh, exiting])])

# Checks for typedefs, structures, and compiler characteristics.

# Checks for library functions.

AC_CONFIG_FILES([Makefile])
AC_OUTPUT
