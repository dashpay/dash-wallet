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


def printHeader(header, text, output):
    print("<" + header + ">" + text + "</" + header +">", file=output)


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

    outputFile = codecs.open("translation-comparison-report.html", "w", "utf-8")
    outputFile.write(u'\ufeff')
    title = "Translation Source Comparison Report: " + str(date.today())
    print("<!DOCTYPE html><html><head>", file=outputFile)
    print("  <title>", end="", file=outputFile)
    print(title, end="", file=outputFile)
    print("  </title>", end="", file=outputFile)
    print('<STYLE type="text/css"">' +
    'H1, H2, H3 { color: blue; font-family: Arial}\n' +
    '.ios { color: red; }\n' +
    '.android { color: green; }\n' +
    '.string { border-width: 1px; border-style: solid }' +
    '</STYLE>', file=outputFile)
    print("</head><body>", file=outputFile)
    print("<h1>", title, "</h1>", sep="", file=outputFile)
    printHorizontalLine(outputFile)
    printHeader("h2", "Source File Summary", outputFile)
    print("<ul>", file=outputFile)
    print("<li>iOS total number of strings:", len(iOSStrings), "in", len(iOSFiles), "files.</li>", file=outputFile)
    print("<li>android total number of strings:", len(androidStrings), "in", len(androidFiles), "files.</li>", file=outputFile)
    for f in iOSFiles:
        print("<li>iOS File:    ", f, "</li>", file=outputFile)
    for f in androidFiles:
        print("<li>Android File:", f, "</li>", file=outputFile)
    print("</ul>", file=outputFile)
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
    printHeader("h2", "<a name='top'>Summary Report iOS vs Android</a>", outputFile)
    printHorizontalLine(outputFile)
    print("<ul>", file=outputFile)
    print("<li><a href='#exact'>", len(found), "strings match exactly between iOS and android</a></li>", file=outputFile)
    print("<li><a href='#nomatch'>", len(iOSStrings), "iOS strings do not match Android</li>", file=outputFile)
    print("<li><a href='#nomatch'>", len(androidStrings), "Android Strings do not match iOS</li>", file=outputFile)
    print("<li><a href='#wildcard'>", withWildcards, "strings have wildcards for iOS</li>", file=outputFile)
    print("<li><a href='#wildcard'>", withWildcardsAndroid, "strings have wildcards for Android</li>", file=outputFile)
    print("<li><a href='#case'>", len(foundUpper), "iOS strings differ by case with Android</a></li>", file=outputFile)
    print("<li><a href='#start'>", len(foundStartsWith), "iOS strings start with words similar to Android</a></li>", file=outputFile)
    print("<li><a href='#punctuation'>", len(foundPunctuation), "iOS strings differ by punctuation with Android</a></li>", file=outputFile)
    print("</ul>", file=outputFile)

    printHeader("h2", "<a name='case'>iOS Strings that differ by case with Android</a>", outputFile)
    printHorizontalLine(outputFile)
    printPreformatedComparisonList(foundUpper, outputFile)

    printHeader("h2", "<a name='start'>iOS Strings start with words similar Android</a>", outputFile)
    printHorizontalLine(outputFile)
    printPreformatedComparisonList(foundStartsWith, outputFile)

    printHeader("h2", "<a name='punctuation'>iOS Strings that differ by punctuation with Android</a>", outputFile)
    printHorizontalLine(outputFile)
    printPreformatedComparisonList(foundPunctuation, outputFile)

    printHeader("h2", "<a name='wildcard'>iOS Strings that differ by wildcard with Android</a>", outputFile)
    printHorizontalLine(outputFile)
    printPreformatedComparisonList(foundWildCards, outputFile)

    printHeader("h2", "<a name='exact'>iOS Strings found in Android</a>", outputFile)
    printHorizontalLine(outputFile)

    for f in found:
        print("<div class=string>", file=outputFile)
        print("<pre>", file=outputFile)
        print("'", f, "': in iOS matches ", found[f], " in Android", sep="", file=outputFile)
        print("</pre>", file=outputFile)
        print("</div>", file=outputFile)

    printHeader("h2", "<a name='wildcard'>Wildcard strings containing %</a>", outputFile)
    printHorizontalLine(outputFile)

    stringsWithWildcardsList.sort(key=mykey)

    for w in stringsWithWildcardsList:
        print("<div class=string>", file=outputFile)
        try:
            if stringsWithWildcards[w][0] == 'i':
                print("<pre class=ios>", file=outputFile)
                print("iOS:     ", end="", file=outputFile)
            else:
                print("<pre class=android>", file=outputFile)
                print("android: ", end="", file=outputFile)
            print("'", w, "' - ", stringsWithWildcards[w], sep="", file=outputFile)
            print("</pre>", file=outputFile)
        except UnicodeEncodeError:
            print("UnicodeDecodeError exception")
        print("</div>", file=outputFile)

    print(file=outputFile)
    printHeader("h2", "<a name='notfound'>List of Strings not found in the other platform</a>", outputFile)
    printHorizontalLine(outputFile)

    listNotFound.sort(key=mykey)

    for l in listNotFound:
        print("<div class=string>", file=outputFile)
        try:
            if notFound[l][0] == 'i':
                print("<pre class=ios>", file=outputFile)
                print("iOS:     ", end="", file=outputFile)
            else:
                print("<pre class=android>", file=outputFile)
                print("android: ", end="", file=outputFile)
            print("'", l, "' - ", notFound[l], sep="", file=outputFile)
            print("</pre>", file=outputFile)
        except UnicodeEncodeError:
            print("UnicodeDecodeError exception")
        print("</div>", file=outputFile)

    print("</body>", file=outputFile)
    print("</html>", file=outputFile)
    outputFile.close()


def printPreformatedComparisonList(found, outputFile):
    if len(found) > 0:

        for f in found:
            print("<div class=string>", file=outputFile)
            print("<pre class=ios>", file=outputFile)
            print("iOS:     '", f, "'", sep="", file=outputFile)
            print("</pre>", file=outputFile)
            print("<pre class=android>", file=outputFile)
            print("android: '", found[f], "'", sep="", file=outputFile)
            print("</pre>", file=outputFile)
            print("</div>", file=outputFile)
    else:
        printParagraph("no results found that match this criteria", outputFile)


def printParagraph(text, outputFile):
    print("<p>" + text + "</p>", file=outputFile)


def printHorizontalLine(outputFile):
    print("<hr />", file=outputFile)


# perform the comparisons
main()
