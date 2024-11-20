rm -rf ./build/distributions
./gradlew distZip && cd ./build/distributions && unzip kotlin-language-server-1.0.0.zip
