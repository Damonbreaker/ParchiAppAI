// In-memory hand-off of the parchi photo, for this tab only (never stored anywhere):
// - the file picked on the start screen, read by the scan screen;
// - the compressed photo (a Blob) from the scan screen to the editor, which takes it
//   once, shows it while the customer edits and drops it when they leave.
// The server reads the photo once to fill the list and does not keep it.

let pickedFile = null;
let scannedBlob = null;

export function setPickedFile(file) {
  pickedFile = file;
}

export function getPickedFile() {
  return pickedFile;
}

export function clearPickedFile() {
  pickedFile = null;
}

export function setScannedPhoto(blob) {
  scannedBlob = blob;
}

// The editor reads it once on open and then clears it here, so it lives only as
// long as the editor screen does.
export function getScannedPhoto() {
  return scannedBlob;
}

export function clearScannedPhoto() {
  scannedBlob = null;
}
