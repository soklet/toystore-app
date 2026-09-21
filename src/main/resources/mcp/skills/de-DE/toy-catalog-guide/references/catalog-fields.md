# Katalogfeldreferenz

`list_toys` und `show_toy_catalog` liefern `summary`, `toys` und `locale`;
`get_toy` liefert `summary` und `toy`. `locale` im Listenergebnis bezeichnet
die vom Server gewählte Sprache der Benutzeroberfläche (`en-US`, `de-DE` oder
`pt-BR`), passend zur Sprache des authentifizierten Kontos. Es ist weder eine
vom Aufrufer vorgegebene Spracheinstellung noch eine Erlaubnis, die gelieferten
Anzeigetexte neu zu formatieren. Jedes Spielzeug enthält:

| Feld | Bedeutung |
| --- | --- |
| `toyId` | Stabile UUID für `get_toy` oder die Ressourcen-URI des Spielzeugs. |
| `name` | Verfasster Katalogname; unverändert übernehmen. |
| `price` | Dezimalbetrag in `currencyCode`, keine ganzzahlige Anzahl kleiner Währungseinheiten. |
| `currencyCode` | ISO-Währungscode. Beträge nur innerhalb derselben Währung vergleichen. |
| `priceDescription` | Für Sprache und Region des authentifizierten Kontos formatierter Preis. |
| `currencySymbol`, `currencyDescription` | Lokalisierte Währungsangaben; ein Symbol allein kann mehrdeutig sein. |
| `createdAt` | Zeitpunkt der Erstellung des Katalogeintrags im ISO-8601-Format. |
| `createdAtDescription` | Dieser Zeitpunkt, formatiert für Sprache, Region und Zeitzone des Kontos. |

Ein Preis von `12.34` mit `currencyCode` `USD` bedeutet zum Beispiel 12,34
US-Dollar, nicht 12,34 Cent. Ein deutsches Konto sieht möglicherweise `12,34 $`
als Anzeigetext. Verwende lokalisierte Anzeigetexte nicht als Rechengrundlage und
rechne ohne einen unabhängig bereitgestellten Wechselkurs keine Währungen um.

Der Erstellungszeitpunkt eines Spielzeugeintrags ist weder ein Erscheinungstermin
noch eine Verfügbarkeitszusage oder Lieferschätzung. Eine erfolgreiche Abfrage
belegt weder einen Lagerbestand noch eine Reservierung.

Die normale Ressource `toystore://toys/<toyId>` enthält ein JSON-Objekt `toy`
mit denselben Feldern, jedoch keine Werkzeug-`summary`. Der Katalog kann sich
zwischen Anfragen ändern. Frage das ausgewählte Spielzeug daher erneut ab,
bevor du einen aktuellen Vergleich präsentierst.
