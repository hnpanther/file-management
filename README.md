
# File Management

This is a program for managing files. This program includes creating files, downloading files, deleting files, creating new versions and formats for a file and store it in file system.



## Run Locally

This project requires Java 21.

Clone the project

```bash
  git clone https://github.com/hnpanther/file-management.git
```
Create a database in mysql and
go to application.properties and set database properties

```bash
  spring.datasource.url=jdbc:mysql://localhost:3306/file_management
  spring.datasource.username=file_management
  spring.datasource.password=file_management
```
Specify a path for the root of the files. For example in Windows(create files/main dircetory before run)

```bash
  file.management.base-dir=D:/files/main/
```

Specify path for log in logback-spring.xml
```bash
  <property name="LOG_PATH" value="D:/files/logs" />
```

Repeat these steps for application.propertes file in the test directory as well(don't create test directory. it's create and delete automatic in tests)
```bash
  spring.datasource.url=jdbc:mysql://localhost:3306/file_management_test
  spring.datasource.username=file_management_test
  spring.datasource.password=file_management_test
  file.management.base-dir=D:/files/test/
```

You can run in standlone mode or build and create war file and deploy on Tomcat

```bash
  mvn clean install
```

Default username and password for admin: 'admin'
