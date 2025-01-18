const startX = 57;
const width = 48;
const height = 36;
const midY = 2;

function generateWeekOffsetString(weekOffset) {
    if (weekOffset === 0) {
        return '';
    }
    if (weekOffset < 0) {
        return ` - ${Math.abs(weekOffset) * 7}`;
    }
    return ` + ${weekOffset * 7}`;
}

function generateDateWrap(weekday, weekOffset, inner) {
    console.log(
`      <PartText x="${startX + width * weekday}" y="${height * (weekOffset + midY)}" width="${width}" height="${height}">
        <Text align="CENTER">
          <Font family="SYNC_TO_DEVICE" size="24" color="#ffffffff">
            <Template>%s
              <Parameter expression="${inner}" />
            </Template>
          </Font>
        </Text>
      </PartText>`
    );
}

function generateDate(weekday, weekOffset) {
    return `icuText(&quot;d&quot;, [UTC_TIMESTAMP] + ((-(([DAY_OF_WEEK] + 5) % 7) + ${weekday}${generateWeekOffsetString(weekOffset)}) * 86400000))`;
}

function generateLine(y) {
    console.log(
`      <PartDraw x="0" y="${y - 1}" width="450" height="2">
        <Line startX="0" startY="1" endX="450" endY="1">
            <Stroke color="#ffffffff" thickness="2" />
        </Line>
      </PartDraw>`
    );
}

generateDateWrap(0, -2, "M");
generateDateWrap(1, -2, "T");
generateDateWrap(2, -2, "W");
generateDateWrap(3, -2, "T");
generateDateWrap(4, -2, "F");
generateDateWrap(5, -2, "S");
generateDateWrap(6, -2, "S");

generateLine(height);

for (let week = -1; week <= 1; week++) {
    for (let day = 0; day < 7; day++) {
        generateDateWrap(day, week, generateDate(day, week));
    }
    generateLine(height * (week + 3));
}