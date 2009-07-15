#!/bin/bash

XE_VERSION=2.0-SNAPSHOT;
GWT_VERSION=1.6.4;

JAVA32_HOME=/usr/lib/jvm/ia32-java-1.5.0-sun/bin;
M2_REPO=~/.m2/repository;
APP_DIR=`dirname $0`/webapps/xwiki;
WYSIWYG_PATH=resources/js/xwiki/wysiwyg/xwe;

$JAVA32_HOME/java \
-Xmx1024m \
-cp \
$APP_DIR/WEB-INF/lib/xwiki-web-wysiwyg-$XE_VERSION.jar:\
$M2_REPO/com/xpn/xwiki/platform/xwiki-web-wysiwyg/$XE_VERSION/xwiki-web-wysiwyg-$XE_VERSION-sources.jar:\
$M2_REPO/com/xpn/xwiki/platform/xwiki-web-gwt/$XE_VERSION/xwiki-web-gwt-$XE_VERSION-sources.jar:\
$M2_REPO/org/xwiki/platform/xwiki-web-gwt-dom/$XE_VERSION/xwiki-web-gwt-dom-$XE_VERSION.jar:\
$M2_REPO/com/google/gwt/incubator-glasspanel/r729/incubator-glasspanel-r729.jar:\
$M2_REPO/com/smartgwt/smartgwt/1.0b2/smartgwt-1.0b2.jar:\
$M2_REPO/com/google/gwt/gwt-dev/$GWT_VERSION/gwt-dev-$GWT_VERSION-linux.jar:\
$M2_REPO/com/google/gwt/gwt-user/$GWT_VERSION/gwt-user-$GWT_VERSION.jar \
com.google.gwt.dev.HostedMode \
-logLevel WARN \
-style DETAILED \
-noserver \
-port 8080 \
-startupUrl xwiki/$WYSIWYG_PATH/Wysiwyg.html \
com.xpn.xwiki.wysiwyg.Wysiwyg