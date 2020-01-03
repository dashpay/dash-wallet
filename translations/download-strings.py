# program:  initialize translation source comparison with iOS
# date:     12/27/19
import os
if not os.path.isdir('iOS'):
    os.mkdir("iOS")
os.system('tx pull -s')

