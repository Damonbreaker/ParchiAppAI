// Short kirana facts shown while OCR / waiting — EN + HI from account language.

const EN = [
  'India grows over 100 varieties of rice — basmati is just the fragrant tip.',
  'Atta for rotis is usually whole wheat; maida is refined and softer for breads.',
  'Toned milk has some fat removed; double-toned has even less — both keep protein.',
  'Arhar (toor) dal is a classic dal tadka base across North and South India.',
  'Mustard oil’s sharp smell fades when you heat it to a gentle smoke point first.',
  'A kilo of onions can lose water weight in storage — buy firm, dry skins.',
  'Jaggery (gur) is less processed than white sugar and still tastes like cane.',
  'Kabuli chana and desi chana are cousins — one pale and large, one darker.',
  'Fresh coriander stems carry as much flavour as the leaves in many curries.',
  'Cold-pressed oils keep more aroma; refined oils handle high-heat frying better.',
  'Hing (asafoetida) blooms in hot oil — a pinch can replace onion-garlic notes.',
  'Besan (gram flour) binds pakoras and also thickens kadhi without lumps if sifted.',
  'Curd sets faster in a warm kitchen; a spoon of old dahi helps the new batch.',
  'Tea leaves vs dust: leaves brew cleaner cups; dust gives a stronger chai colour.',
  'Store potatoes in a cool dark place — fridge cold can turn starch into sugar.',
  'Turmeric stains fade with soap and sun; keep a dedicated cutting board if you can.',
  'Eggs last longer tip-down in the fridge so the yolk stays centred.',
  'Rock salt (sendha) is often preferred on fasting days in many Indian homes.',
  'Sooji (rava) toasts in ghee before upma so it stays fluffy, not sticky.',
  'A bay leaf in rice water adds aroma without overpowering the grain.',
];

const HI = [
  'भारत में चावल की 100 से ज़्यादा किस्में उगती हैं — बासमती सिर्फ सुगंधित शुरुआत है।',
  'रोटी वाला आटा आमतौर पर साबुत गेहूँ होता है; मैदा महीन और नरम होता है।',
  'टोन्ड दूध में थोड़ा फैट कम होता है; डबल-टोन्ड में और भी कम — प्रोटीन रहता है।',
  'अरहर (तूर) दाल उत्तर-दक्षिण दोनों में दाल तड़का की आम बेस है।',
  'सरसों तेल की तेज़ गंध हल्की आँच पर गरम करने पर कम हो जाती है।',
  'प्याज़ नमी छोड़ सकता है — सख्त और सूखी छिलके वाले चुनें।',
  'गुड़ सफेद चीनी से कम प्रोसेस्ड होता है और गन्ने जैसा स्वाद रखता है।',
  'काबुली चना हल्का और बड़ा होता है; देसी चना थोड़ा गहरा।',
  'धनिया की डंठलों में भी पत्तियों जितना स्वाद होता है।',
  'कोल्ड-प्रेस्ड तेल में खुशबू ज़्यादा रहती है; रिफ़ाइंड तेल तेज़ आँच सहन करता है।',
  'हींग गरम तेल में खिलती है — एक चुटकी प्याज़-लहसुन जैसा असर दे सकती है।',
  'बेसन पकोड़े बाँधता है और कढ़ी को बिना गुठली के गाढ़ा करता है।',
  'दही गर्म रसोई में जल्दी जमता है; पुरानी दही का चम्मच मदद करता है।',
  'पत्ती वाली चाय साफ़ कप देती है; धूल वाली चाय रंग में तेज़ होती है।',
  'आलू को ठंडी अँधेरी जगह रखें — फ्रिज की ठंड स्टार्च को मीठा कर सकती है।',
  'हल्दी के दाग साबुन और धूप से फीके पड़ते हैं।',
  'अंडे फ्रिज में नोक नीचे रखने से जर्दी बीच में रहती है।',
  'सेंधा नमक कई घरों में व्रत के दिन पसंद किया जाता है।',
  'उपमा से पहले सूजी को घी में भूनें ताकि फूलकर अलग रहे।',
  'चावल के पानी में तेजपत्ता हल्की खुशबू देता है।',
];

export function groceryFacts(lang) {
  return lang === 'hi' ? HI : EN;
}

export function randomFact(lang, avoid) {
  const list = groceryFacts(lang);
  if (!list.length) return '';
  if (list.length === 1) return list[0];
  let next = list[Math.floor(Math.random() * list.length)];
  let guard = 0;
  while (next === avoid && guard < 6) {
    next = list[Math.floor(Math.random() * list.length)];
    guard += 1;
  }
  return next;
}
