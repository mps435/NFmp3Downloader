Set WshShell = CreateObject("WScript.Shell")
' פקודה זו סוגרת את כל תהליכי ה-javaw שמריצים את השירות הספציפי
WshShell.Run "taskkill /F /FI ""WINDOWTITLE eq TextFileFlatSyncService*""", 0, True
WshShell.Run "taskkill /F /IM javaw.exe /FI ""COMMANDLINE eq *TextFileFlatSyncService*""", 0, True