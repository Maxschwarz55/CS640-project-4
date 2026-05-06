.PHONY: all ant transport clean

all: ant transport

ant:
	ant

transport:
	chmod +x transport/build.sh
	cd transport && ./build.sh
	cd ..

clean:
	ant clean
