import "./styles.scss";

if ("serviceWorker" in navigator) {
    window.addEventListener("load", async () => {
        navigator.serviceWorker.register("./sw.js").then(registration => {
        }).catch(registrationError => {
            console.log('SW registration failed: ', registrationError);
        });
    });
}
const YEAR_LENGTH = 2;
const MONTH_LENGTH = 1;
const DAY_LENGTH = 1;
const HOUR_LENGTH = 1;
const MINUTE_LENGTH = 1;
const SECOND_LENGTH = 1;
const CTS_CHAR_LENGTH = YEAR_LENGTH + MONTH_LENGTH + DAY_LENGTH + HOUR_LENGTH + MINUTE_LENGTH + SECOND_LENGTH;


function valueToVolts(g: number) {
    let y = (179 * g) / 100 + 711;
    let x = 9 * y / 2560;
    return x;
}

document.querySelector("#cts-write").addEventListener("click", async () => {

    // let paired = await navigator.bluetooth.getDevices();
    // console.log(paired);

    let digitServiceUUID = '00001523-1212-efde-1523-785fef13d123';
    let devices = await navigator.bluetooth.requestDevice({
        filters: [
            { services: ['battery_service'], name: 'Digit' }
        ],
        optionalServices: [digitServiceUUID]
    });
    let discoveredServices = await devices.gatt.connect();




    let service = await discoveredServices.getPrimaryService(digitServiceUUID);

    let characteristic = await service.getCharacteristic('00001805-1212-efde-1523-785fef13d123');
    console.log(characteristic);
    let cts = new Uint8Array(CTS_CHAR_LENGTH);
    let view = new DataView(cts.buffer);
    let date = new Date();
    let yearValue = date.getFullYear() + 1900;
    view.setUint16(0, yearValue, true);
    view.setUint8(2, date.getMonth() + 1);
    view.setUint8(3, date.getDate());
    view.setUint8(4, date.getHours());
    view.setUint8(5, date.getMinutes());
    view.setUint8(6, date.getSeconds());
    await characteristic.writeValue(cts);

    let batteryService = await discoveredServices.getPrimaryService("battery_service");
    let batteryLevel = await batteryService.getCharacteristic("battery_level");
    let batteryValue = await batteryLevel.readValue();

    let el = document.querySelector("#battery-level");


    let g = batteryValue.getUint8(0);

    let numberFormat = new Intl.NumberFormat('de-AT', {maximumFractionDigits: 2});

    el.innerHTML = `Battery Level: ${g}% (${numberFormat.format(valueToVolts(g))}V)`;



});