//
// Created by hashengineering on 12/1/18.
//

#include <stdio.h>

//
// This file adds stdin, stdout and stderr for API < 23
//

#if __ANDROID_API__ < __ANDROID_API_M__
#undef stdin
#undef stdout
#undef stderr
extern "C" {

FILE * stdin (&__sF[0]);
FILE * stdout (&__sF[1]);
FILE * stderr (&__sF[2]);

}
#endif