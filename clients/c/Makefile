CC ?= clang
CFLAGS ?= -Wall -Wextra -O3 -Iinclude -std=c99
AR ?= ar

SRC = src/ferrite.c
OBJ = $(SRC:.c=.o)
LIB = libferrite.a
EXAMPLE = bin/example

all: $(LIB) $(EXAMPLE)

$(LIB): $(OBJ)
	$(AR) rcs $@ $^

src/%.o: src/%.c
	$(CC) $(CFLAGS) -c $< -o $@

$(EXAMPLE): example.c $(LIB)
	mkdir -p bin
	$(CC) $(CFLAGS) $< -L. -lferrite -o $@

clean:
	rm -rf src/*.o $(LIB) bin
