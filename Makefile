
all: none


format:
	clang-format -i -style=google app/src/main/java/com/facebook/audiolat/TestSettings.java
	clang-format -i -style=google app/src/main/java/com/facebook/audiolat/MainActivity.java
	clang-format -i -style=google app/src/main/jni/aaudio.cpp
	clang-format -i -style=google app/src/main/java/com/facebook/audiolat/JavaAudio.java


