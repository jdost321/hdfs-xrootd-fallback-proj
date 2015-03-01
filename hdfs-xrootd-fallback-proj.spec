%define lib_hadoop_dirname /usr/lib
%define lib_hadoop %{lib_hadoop_dirname}/hadoop
%define lib_hdfs %{lib_hadoop_dirname}/hadoop-hdfs

Name:           hdfs-xrootd-fallback-proj
Version:        1.0.0
Release:        4%{?dist}
Summary:        Tools to enable relaxed local Hadoop replication
Group:          System Environment/Daemons
License:        BSD
URL:            http://www.gled.org/cgi-bin/twiki/view/Main/HdfsXrootd
Source0:        %{name}-%{version}.tar.gz
BuildRoot:      %{_tmppath}/%{name}-%{version}-%{release}-root-%(%{__id_u} -n)

BuildRequires: java7-devel
BuildRequires: pcre-devel
BuildRequires: xrootd-client-devel >= 4.0.4
BuildRequires: hadoop-hdfs >= 2.0.0+545-1.cdh4.1.1.p0.19.osg

%description
The HDFS XRootD Fallback system enables relaxed local Hadoop replication by
utilizing global redundancy provided by the XRootD Federation. This system
provides exception handling at the block level, a cache to locally store
repaired blocks retrieved from the Federation, and the ability to re-inject the
repaired blocks back into Hadoop.

%package -n hdfs-xrootd-fallback
Summary:        Hadoop extension to interface with XRootD for block-level read error prevention
Group:          System Environment/Daemons
Requires: pcre
Requires: xrootd-client-libs >= 4.0.4
Requires: hadoop-hdfs >= 2.0.0+545-1.cdh4.1.1.p0.19.osg
Requires: hadoop-hdfs-fuse >= 2.0.0+545-1.cdh4.1.1.p0.19.osg

%description -n hdfs-xrootd-fallback
The HDFS XRootD Fallback package is installed on every datanode in the Hadoop
cluster and accesses blocks on demand via XRootD Cache on failed read exceptions

%package -n hdfs-xrootd-healer
Summary:        Daemon that periodically re-injects cached blocks back into hadoop
Group:          System Environment/Daemons
Requires: hadoop-hdfs >= 2.0.0+545-1.cdh4.1.1.p0.19.osg

%description -n hdfs-xrootd-healer
The HDFS XRootD Healer is installed on the XRootD Cache node and periodically
compares corrupt blocks in Hadoop with blocks stored in the cache. It re-injects
the repaired blocks once they are fully cached.

%prep
%setup -q

%build
%configure \
HADOOP_HOME=%{lib_hadoop} \
HADOOP_HDFS_HOME=%{lib_hdfs} \
CPPFLAGS=-I/usr/include/xrootd

make %{?_smp_mflags}

%install
rm -rf %{buildroot}
%make_install

mkdir -p %{buildroot}/%{_sysconfdir}/hdfs-xrootd-healer
mkdir -p %{buildroot}/%{_initrddir}
mkdir -p %{buildroot}/%{_sysconfdir}/cron.d
mkdir -p %{buildroot}/%{_sysconfdir}/logrotate.d
install -p -m 644 %{buildroot}/%{_datadir}/hdfs-xrootd-healer/hdfs-xrootd-healer.cfg \
  %{buildroot}/%{_sysconfdir}/hdfs-xrootd-healer
install -p %{buildroot}/%{_datadir}/hdfs-xrootd-healer/hdfs-xrootd-healer.init \
  %{buildroot}/%{_initrddir}/hdfs-xrootd-healer
install -p -m 644 %{buildroot}/%{_datadir}/hdfs-xrootd-healer/hdfs-xrootd-healer.cron \
  %{buildroot}/%{_sysconfdir}/cron.d/hdfs-xrootd-healer
install -p -m 644 %{buildroot}/%{_datadir}/hdfs-xrootd-healer/hdfs-xrootd-healer.logrotate \
  %{buildroot}/%{_sysconfdir}/logrotate.d/hdfs-xrootd-healer
mkdir -p %{buildroot}/%{_localstatedir}/lock/hdfs-xrootd-healer

%clean
rm -rf %{buildroot}

%post -n hdfs-xrootd-fallback -p /sbin/ldconfig
%postun -n hdfs-xrootd-fallback -p /sbin/ldconfig

%pre -n hdfs-xrootd-healer
getent group hdfshealer >/dev/null || groupadd -r hdfshealer
getent passwd hdfshealer >/dev/null || \
  useradd -r -g hdfshealer -d %{_sysconfdir}/hdfs-xrootd-healer -s /bin/bash \
  -c "HDFS XRootD Healer User" hdfshealer
exit 0

%post -n hdfs-xrootd-healer
if [ $1 = 1 ];then
  /sbin/chkconfig --add hdfs-xrootd-healer
fi

%preun -n hdfs-xrootd-healer
if [ $1 = 0 ];then
  /sbin/service hdfs-xrootd-healer stop >/dev/null 2>&1 || :
  /sbin/chkconfig --del hdfs-xrootd-healer
fi

%files -n hdfs-xrootd-fallback
%defattr(-,root,root,-)
%doc hdfs-xrootd-fallback/README
%doc hdfs-xrootd-fallback/LICENSE
%{lib_hadoop}/client/hdfs-xrootd-fallback-%{version}.jar
%{_libdir}/libXrdBlockFetcher.so*
%config(noreplace) %{_sysconfdir}/hadoop/conf.osg/xfbfs-site.xml

%files -n hdfs-xrootd-healer
%defattr(-,root,root,-)
%doc README LICENSE
%{_libdir}/hdfs-xrootd-healer
%{_libexecdir}/hdfs-xrootd-healer
%{_datadir}/hdfs-xrootd-healer
%attr(-,hdfshealer,hdfshealer) %dir %{_sysconfdir}/hdfs-xrootd-healer
%config(noreplace) %{_sysconfdir}/hdfs-xrootd-healer/hdfs-xrootd-healer.cfg
%{_initrddir}/hdfs-xrootd-healer
%{_sysconfdir}/cron.d/hdfs-xrootd-healer
%{_sysconfdir}/logrotate.d/hdfs-xrootd-healer
%attr(-,hdfshealer,hdfshealer) %{_localstatedir}/lock/hdfs-xrootd-healer
%attr(-,hdfshealer,hdfshealer) %dir %{_localstatedir}/log/hdfs-xrootd-healer

%changelog
* Thu Dec 24 2014 Jeff Dost <jdost@ucsd.edu> - 1.0.0-4
- Add healer rpm

* Thu Dec 4 2014 Jeff Dost <jdost@ucsd.edu> - 1.0.0-3
- Rebuild against xrootd4

* Thu Apr 3 2014 Jeff Dost <jdost@ucsd.edu> - 1.0.0-2
- Bug fix in libXrdBlockFetcher.so  

* Fri Feb 14 2014 Jeff Dost <jdost@ucsd.edu> - 1.0.0-1
- Initial release