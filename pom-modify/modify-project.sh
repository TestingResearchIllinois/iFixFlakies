#!/bin/bash

DEFAULT_POM="1.0.0-SNAPSHOT"

if [[ $1 == "" ]]; then
    echo "arg1 - the path to the project, where high-level pom.xml is"
    echo "arg2 - (Optional) Custom version for the artifact (e.g., 1.0.1, 1.0.2-SNAPSHOT). Default is $DEFAULT_POM"
    exit
fi

if [[ ! $2 == "" ]]; then
    DEFAULT_POM=$2
fi

crnt=`pwd`
working_dir=`dirname $0`
project_path=$1

cd ${project_path}
project_path=`pwd`
cd - > /dev/null

cd ${working_dir}

javac PomFile.java
find ${project_path} -name pom.xml | grep -v "src/" | java PomFile ${DEFAULT_POM}
rm -f PomFile.class

cd ${crnt}
