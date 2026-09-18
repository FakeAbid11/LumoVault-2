package com.lumovault.lumovault.features.gallery.data.service

/**
 * Generic search categories an ImageNet class name belongs to.
 *
 * Ported from the `_hypernyms` map in
 * lib/features/gallery/data/services/image_classifier_service.dart.
 *
 * Search matches keywords as substrings of the stored labels, so an
 * expansion is only needed when the category word is NOT a substring of
 * the class name already - a 'tabby' photo must answer to 'cat' (no
 * substring relation), while searching 'shark' already matches
 * 'great white shark' without help. Matched with word boundaries against
 * the lowercased class name, so 'catamaran'/'caterpillar' never match
 * 'cat' while 'Egyptian cat' matches 'cat'.
 */
val imageNetHypernyms: Map<String, List<String>> = mapOf(
    "cat" to listOf("cat", "tabby", "lynx", "cougar", "panther", "jaguar", "leopard", "cheetah", "siamese", "persian"),
    "dog" to listOf("dog", "retriever", "spaniel", "terrier", "hound", "shepherd", "poodle", "bulldog", "schnauzer", "corgi", "husky", "malamute", "dalmatian", "boxer", "beagle", "collie", "pointer", "chow", "pekingese", "pekinese", "pinscher", "schipperke", "malinois", "borzoi", "whippet", "vizsla", "weimaraner", "basenji", "basset", "komondor", "keeshond", "pug"),
    "bird" to listOf("bird", "finch", "sparrow", "robin", "eagle", "owl", "parrot", "cockatoo", "macaw", "gull", "loon", "swan", "goose", "duck", "chicken", "cock", "hen", "turkey", "peacock", "hummingbird", "woodpecker", "kingfisher", "jay", "magpie", "crow", "raven", "falcon", "hawk", "ostrich", "penguin", "flamingo", "heron", "stork", "quail", "partridge", "pheasant", "kiwi"),
    "fish" to listOf("fish", "shark", "ray", "stingray", "skate", "trout", "salmon", "carp", "perch", "bass", "eel", "herring", "barracouta", "gar", "coho"),
    "car" to listOf("car", "convertible", "jeep", "sedan", "limousine", "taxi", "minivan", "racer"),
    "truck" to listOf("truck", "pickup", "tractor", "trailer"),
    "bus" to listOf("bus", "minibus", "trolleybus"),
    "train" to listOf("train", "locomotive", "streetcar", "tram"),
    "boat" to listOf("boat", "canoe", "kayak", "catamaran", "gondola", "ferry", "speedboat", "house boat"),
    "plane" to listOf("plane", "airliner", "jet"),
    "horse" to listOf("horse", "pony", "mustang", "stallion", "clydesdale"),
    "cow" to listOf("cow", "cattle", "bull", "ox", "heifer", "steer"),
    "sheep" to listOf("sheep", "lamb", "ewe", "ram"),
    "goat" to listOf("goat", "ibex", "kid"),
    "pig" to listOf("pig", "hog", "boar", "swine", "piglet"),
    "deer" to listOf("deer", "elk", "moose", "antelope", "gazelle", "caribou"),
    "monkey" to listOf("monkey", "chimpanzee", "gorilla", "orangutan", "ape", "baboon", "macaque", "lemur", "marmoset"),
    "bear" to listOf("bear", "panda"),
    "snake" to listOf("snake", "cobra", "viper", "python", "boa", "mamba", "asp"),
    "rabbit" to listOf("rabbit", "hare", "bunny"),
    "fruit" to listOf("banana", "apple", "orange", "lemon", "lime", "peach", "pear", "plum", "cherry", "strawberry", "pineapple", "grape", "watermelon", "cantaloupe", "mango", "kiwi", "apricot", "pomegranate"),
    "vegetable" to listOf("broccoli", "carrot", "cucumber", "eggplant", "mushroom", "onion", "garlic", "tomato", "potato", "zucchini", "spaghetti squash", "artichoke", "bell pepper"),
    "flower" to listOf("rose", "tulip", "daisy", "dandelion", "orchid", "daffodil", "lily", "lilac", "hibiscus", "petunia", "peony", "poppy"),
)
