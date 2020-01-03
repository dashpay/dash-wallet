# Sync Translations
import codecs
import xml.dom.minidom
import re
from datetime import date


androidFiles = {"../wallet/res/values/strings.xml",
                "../wallet/res/values/strings-extra.xml",
                "../common/src/main/res/values/strings.xml",
                "../uphold-integration/src/main/res/values/strings-uphold.xml"
                }

iOSFiles = {"iOS/app-localizable-strings.strings", "iOS/dashsync-localizable-strings.strings"}

# primary lists
androidStrings = {}
iOSStrings = []


def loadAndroidFiles():
    '''
        Load all of the android strings into a dictionary
        The key is the string
        The value is the android string id
    '''
    for fileName in androidFiles:
        doc = xml.dom.minidom.parse(fileName)
        for string in doc.firstChild.childNodes:
            try:
                if string.nodeName == "string":
                    androidStrings[string.firstChild.data] = string.getAttribute("name")
            except AttributeError:
                pass


def loadiOSFiles():
    '''
        load all of the iOS strings into a list
    '''
    for fileName in iOSFiles:
        file = open(fileName+"-utf8", "r")
        for line in file:
            if line.startswith("\""):
                end = line.find('\"', 1)
                if end != -1:
                    iOSStrings.append(line[1:end])


def formatiOSFiles():
    '''Convert the iOS string format of UTF-16LE to UTF8'''
    for fileName in iOSFiles:
        with open(fileName, 'rb') as source_file:
            with open(fileName + "-utf8", 'w+b') as dest_file:
                contents = source_file.read()
                dest_file.write(contents.decode('utf-16').encode('utf-8'))


def mykey(val):
    return val.upper()


def main():
    loadAndroidFiles()
    #print(androidStrings)
    formatiOSFiles()
    loadiOSFiles()
    #print(iOSStrings)

    found = {}
    notFound = {}
    listNotFound = []
    withWildcards = 0
    withWildcardsAndroid = 0
    iOSStringsWithWildcards = []
    androidStringsWithWildcards = []
    stringsWithWildcards = {}
    stringsWithWildcardsList = []

    outputFile = codecs.open("translation-comparison-report.txt", "w", "utf-8")
    outputFile.write(u'\ufeff')
    print("Translation Source Comparison Report: ", date.today(), file=outputFile)
    print("-------------------------------------------------------", file=outputFile)
    print("iOS total number of strings:", len(iOSStrings), "in", len(iOSFiles), "files.", file=outputFile)
    print("android total number of strings:", len(androidStrings), "in", len(androidFiles), "files.", file=outputFile)
    for f in iOSFiles:
        print("iOS File:    ", f, file=outputFile)
    for f in androidFiles:
        print("Android File:", f, file=outputFile)

    # find exact matches
    for i in iOSStrings:
        if i.find("%@") != -1:
            withWildcards += 1
            iOSStringsWithWildcards.append(i)
            stringsWithWildcards[i] = "iOS"
            stringsWithWildcardsList.append(i)
        if i in androidStrings:
            found[i] = androidStrings[i]
        else:
            notFound[i] = "iOS"
            listNotFound.append(i)

    for f in found:
        if f in androidStrings:
            del androidStrings[f]
        if f in iOSStrings:
            iOSStrings.remove(f)

    # compare case
    iOSStringsUpper = {}
    androidStringsUpper = {}
    foundUpper = {}
    foundStartsWith = {}

    for i in iOSStrings:
        iOSStringsUpper[i.upper()] = i

    for a in androidStrings:
        androidStringsUpper[a.upper()] = a
        if a.find("%") != -1:
            withWildcardsAndroid += 1
            androidStringsWithWildcards.append(a)
            stringsWithWildcards[a] = "android - " + androidStrings[a]
            stringsWithWildcardsList.append(a)

    for i in iOSStringsUpper:
        if i in androidStringsUpper:
            foundUpper[iOSStringsUpper[i]] = androidStringsUpper[i]
            del androidStringsUpper[i]
            try:
                del notFound[iOSStringsUpper[i]]
                listNotFound.remove(iOSStringsUpper[i])
            except KeyError:
                pass
            try:
                del notFound[androidStringsUpper[i]]
                listNotFound.remove(androidStringsUpper[i])
            except KeyError:
                pass
        else:
            # look for strings that start with the same words
            for a in androidStringsUpper:
                if a.startswith(i):
                    foundStartsWith[iOSStringsUpper[i]] = androidStringsUpper[a]

    for a in androidStringsUpper:
        if a not in iOSStringsUpper:
            for i in iOSStringsUpper:
                if i.startswith(a):
                    foundStartsWith[iOSStringsUpper[i]] = androidStringsUpper[a]

    # find differences in punctuation:
    iOSStringsUpperNoPunctuation = {}
    androidStringsUpperNoPunctuation = {}
    for i in iOSStringsUpper:
        iOSStringsUpperNoPunctuation[re.sub("[,.!?;:]", "", i)] = iOSStringsUpper[i]

    for a in androidStringsUpper:
        androidStringsUpperNoPunctuation[re.sub("[,.!?;:]", "", a)] = androidStringsUpper[a]

    foundPunctuation = {}
    for i in iOSStringsUpperNoPunctuation:
        if i in androidStringsUpperNoPunctuation:
            foundPunctuation[iOSStringsUpperNoPunctuation[i]] = androidStringsUpperNoPunctuation[i]

    for a in androidStringsUpperNoPunctuation:
        if a in iOSStringsUpperNoPunctuation:
            foundPunctuation[iOSStringsUpperNoPunctuation[a]] = androidStringsUpperNoPunctuation[a]

    # find differences in strings with wildcards:
    foundWildCards = {}
    iOSStringsUpperNoWildCards = {}
    androidStringsUpperNoWildCards = {}
    for i in iOSStringsUpper:
        iOSStringsUpperNoWildCards[re.sub("%[@]", "", i)] = iOSStringsUpper[i]

    for a in androidStringsUpper:
        androidStringsUpperNoWildCards[re.sub("%[sd]", "", a)] = androidStringsUpper[a]

    for i in iOSStringsUpperNoWildCards:
        if i in androidStringsUpperNoWildCards:
            foundWildCards[iOSStringsUpperNoWildCards[i]] = androidStringsUpperNoWildCards[i]

    for a in androidStringsUpperNoWildCards:
        if a in iOSStringsUpperNoWildCards:
            foundWildCards[iOSStringsUpperNoPunctuation[a]] = androidStringsUpperNoWildCards[a]


    # add the remaining non matching android strings to the not found lists
    for a in androidStrings:
        notFound[a] = "android -- " + androidStrings[a]
        listNotFound.append(a)

    # print report
    print(file=outputFile)
    print("Summary Report iOS vs Android", file=outputFile)
    print("-------------------------------------", file=outputFile)
    print(len(found), "strings match exactly between iOS and android", file=outputFile)
    print(len(iOSStrings), "iOS strings do not match Android", file=outputFile)
    print(len(androidStrings), "Android Strings do not match iOS", file=outputFile)
    print(withWildcards, "strings have wildcards for iOS", file=outputFile)
    print(withWildcardsAndroid, "strings have wildcards for Android", file=outputFile)
    print(len(foundUpper), "iOS strings differ by case with Android", file=outputFile)
    print(len(foundStartsWith), "iOS strings start with words similar to Android", file=outputFile)
    print(len(foundPunctuation), "iOS strings differ by punctuation with Android", file=outputFile)

    print(file=outputFile)
    print("iOS Strings that differ by case with Android", file=outputFile)
    print("--------------------------------------------", file=outputFile)
    for f in foundUpper:
        print("iOS:     '", f, "'", sep="", file=outputFile)
        print("android: '", foundUpper[f], "'", sep="", file=outputFile)

    print(file=outputFile)
    print("iOS Strings start with words similar Android", file=outputFile)
    print("--------------------------------------------", file=outputFile)
    for f in foundStartsWith:
        print("iOS:     '", f, "'", sep="", file=outputFile)
        print("android: '", foundStartsWith[f], "'", sep="", file=outputFile)

    print(file=outputFile)
    print("iOS Strings that differ by punctuation with Android", file=outputFile)
    print("--------------------------------------------", file=outputFile)
    for f in foundPunctuation:
        print("iOS:     '", f, "'", sep="", file=outputFile)
        print("android: '", foundPunctuation[f], "'", sep="", file=outputFile)

    print(file=outputFile)
    print("iOS Strings that differ by wildcard with Android", file=outputFile)
    print("--------------------------------------------", file=outputFile)
    for f in foundWildCards:
        print("iOS:     '", f, "'", sep="", file=outputFile)
        print("android: '", foundPunctuation[f], "'", sep="", file=outputFile)

    print(file=outputFile)
    print("iOS Strings found in Android", file=outputFile)
    print("-------------------------------------", file=outputFile)
    for f in found:
        print(f, ": in iOS matches", found[f], "in Android", file=outputFile)

    print(file=outputFile)
    print("Wildcard strings containing %", file=outputFile)
    print("--------------------------------------------", file=outputFile)

    stringsWithWildcardsList.sort(key=mykey)

    for w in stringsWithWildcardsList:
        try:
            if stringsWithWildcards[w][0] == 'i':
                print("iOS:     ", end="", file=outputFile)
            else:
                print("android: ", end="", file=outputFile)
            print(w, " - ", stringsWithWildcards[w], file=outputFile)
        except UnicodeEncodeError:
            print("UnicodeDecodeError exception")

    print(file=outputFile)
    print("iOS Strings not found in Android", file=outputFile)
    print("--------------------------------------------", file=outputFile)

    listNotFound.sort(key=mykey)

    for l in listNotFound:
        try:
            if notFound[l][0] == 'i':
                print("iOS:     ", end="", file=outputFile)
            else:
                print("android: ", end="", file=outputFile)
            print(l, " - ", notFound[l], file=outputFile)
        except UnicodeEncodeError:
            print("UnicodeDecodeError exception")
    outputFile.close()

# perform the comparisons
main()
