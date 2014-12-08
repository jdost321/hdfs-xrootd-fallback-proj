#                                               -*- Autoconf -*-
# Process this file with autoconf to produce a configure script.

AC_PREREQ([2.63])
AC_INIT([hdfs-xrootd-fallback-proj], [1.0.0])
AC_CONFIG_SRCDIR([hdfs-xrootd-fallback/XrdBlockFetcher.cxx])

# Checks for programs.
AC_PROG_CXX
#AC_PROG_INSTALL
AC_PROG_LN_S

# Checks for libraries.

# Checks for header files.
AC_LANG([C++])
AC_CHECK_HEADERS([pcrecpp.h], [],
  [AC_MSG_ERROR([cannot find pcrecpp.h, exiting])])
AC_CHECK_HEADERS([XrdClient/XrdClient.hh], [],
  [AC_MSG_ERROR([cannot find XrdClient/XrdClient.hh, exiting])])

# Checks for typedefs, structures, and compiler characteristics.

# Checks for library functions.

AC_CONFIG_FILES([Makefile
                 hdfs-xrootd-fallback/Makefile
                 hdfs-xrootd-healer/Makefile])
AC_OUTPUT
