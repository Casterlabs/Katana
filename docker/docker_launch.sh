#!/bin/sh
cd data
java \
	-Xms1M \
	-Dkatana.cai.agree=true \
	-cp ../bcutil-jdk18on-1.79.jar:../bcprov-jdk18on-1.79.jar:../bcpg-jdk18on-1.79.jar:../bcpkix-jdk18on-1.79.jar:../Katana.jar \
	co.casterlabs.katana.Launcher