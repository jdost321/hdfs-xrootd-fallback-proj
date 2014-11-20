Name:           hdfs-xrootd-fallback
Version:        1.0.0
Release:        2%{?dist}
Summary:        Hadoop extension to interface with xrootd for block healing
Group:          System Environment/Daemons
License:        BSD
URL:            http://www.gled.org/cgi-bin/twiki/view/Main/HdfsXrootd
Source0:        %{name}-%{version}.tar.gz
BuildRoot:      %{_tmppath}/%{name}-%{version}-%{release}-root-%(%{__id_u} -n)

BuildRequires: java7-devel
BuildRequires: pcre-devel
BuildRequires: xrootd-client-devel
BuildRequires: hadoop-hdfs >= 2.0.0+545-1.cdh4.1.1.p0.19.osg

Requires: pcre
Requires: xrootd-client-libs
Requires: hadoop-hdfs >= 2.0.0+545-1.cdh4.1.1.p0.19.osg
Requires: osg-se-hadoop-client

%description
Hadoop extension to interface with xrootd for block healing

%prep
%setup -q

%build
#%%configure
make %{?_smp_mflags}

%install
rm -rf %{buildroot}
make install DESTDIR=%{buildroot} prefix=/usr sysconfdir=/etc

%clean
rm -rf %{buildroot}

%post -p /sbin/ldconfig
%postun -p /sbin/ldconfig

%files
%defattr(-,root,root,-)
%doc README LICENSE
/usr/lib/hadoop/client/hdfs-xrootd-fallback-%{version}.jar
%{_libdir}/libXrdBlockFetcher.so*
%config(noreplace) %{_sysconfdir}/hadoop/conf.osg/xfbfs-site.xml

%changelog
* Thu Apr 3 2014 Jeff Dost <jdost@ucsd.edu> - 1.0.0-2
- Bug fix in libXrdBlockFetcher.so  

* Fri Feb 14 2014 Jeff Dost <jdost@ucsd.edu> - 1.0.0-1
- Initial release
