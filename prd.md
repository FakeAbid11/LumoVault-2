LumoVault

Master Product Requirements Document — PRD

Product: LumoVault
Platform: Android
Technology: Native Kotlin + Jetpack Compose
Storage backend: User's Telegram account
Primary remote storage: Telegram channel named LumoVault Backup
Document status: Master specification / source of truth
Onboarding: 6 main screens
Build strategy: GitHub Actions / cloud builds

1. Product Overview

1.1 What is LumoVault?

LumoVault is a private Android photo, video, and GIF library with cloud backup powered by the user's Telegram account.

The application is designed to feel like a modern photo-library application rather than a Telegram client.

The user's phone contains the local media library.

Telegram contains the remote backup library.

LumoVault connects the two.

                    LumoVault
                        │
             ┌──────────┴──────────┐
             │                     │
             ▼                     ▼
       📱 LOCAL LIBRARY       ☁️ CLOUD LIBRARY
          MediaStore          Telegram channel
             │                     │
             └──────────┬──────────┘
                        ▼
                 Backup Engine
                        │
                 Backup Database
                        │
                  Sync / Restore

The product's core promise is:

Your photos. Your storage.

LumoVault should make the user's Telegram storage feel like a private cloud photo library.

2. Core Product Principles

LumoVault must follow these principles.

2.1 Local-first

The local Photos library must remain useful even when there is no internet connection.

The user should still be able to:

browse local photos

browse videos

browse GIFs

open local media

view metadata

use albums

use favorites

use archive

use trash

use the map for locally available metadata

Cloud functionality should degrade gracefully when offline.

2.2 Cloud is separate from local storage

LumoVault has two different concepts:

Photos

Media currently available on the device.

Cloud

Media stored in the user's LumoVault Backup Telegram channel.

A media item can therefore exist in four states:

LOCAL ONLY

📱 ✓
☁️ —


LOCAL + CLOUD

📱 ✓
☁️ ✓


CLOUD ONLY

📱 —
☁️ ✓


UPLOADING

📱 ✓
☁️ ↑

The Cloud library must not require all original media to be downloaded to the phone just to display the library.

3. Supported Media

LumoVault supports:

Images

JPEG

PNG

HEIC/HEIF where supported

GIF

other supported Android image MIME types where appropriate

Video

Common Android-supported video formats.

GIF

GIF is a first-class media type.

GIF requirements:

scan local GIFs

show GIF thumbnails

show GIFs in Photos

show GIFs in Cloud

animate GIFs in viewer where practical

preserve original GIF

upload original GIF

download original GIF

track backup state

support manual backup

support restore

The original overview explicitly added GIF support as part of the core LumoVault media model.

4. Backup Quality

LumoVault must preserve the original media rather than unnecessarily converting it.

For backups:

no resizing by LumoVault

no recompression by LumoVault

no unnecessary transcoding

preserve original resolution

preserve original file type

preserve original file size

preserve available metadata

preserve original GIF file

preserve original video file

The Cloud UI may use smaller previews/thumbnails for performance.

That does not mean the original backup is reduced in quality.

LOCAL ORIGINAL
      │
      ▼
LumoVault Backup Engine
      │
      ▼
Telegram
      │
      ├── thumbnail/preview → Cloud UI
      │
      └── original → stored remotely

The original design specifically distinguishes Cloud previews from the original stored media.

5. Telegram Storage Architecture

5.1 Dedicated channel

LumoVault uses a Telegram channel named:

LumoVault Backup

After Telegram authentication, LumoVault should:

Search for the channel.

Determine whether a valid LumoVault channel exists.

If valid channel exists, use it.

If no valid channel exists, create one.

Save the Telegram chat ID locally.

Scan its existing history.

Populate the Cloud library.

6. Channel Validation

The channel must not be identified only by its visible name.

A random Telegram channel could also be called:

LumoVault Backup

Therefore LumoVault should use an internal marker.

Example:

LUMOVAULT_BACKUP
version: 1

The channel discovery process becomes:

Find "LumoVault Backup"
        │
        ▼
Check LumoVault marker
        │
    ┌───┴────┐
    ▼        ▼
  VALID    INVALID
    │        │
    ▼        ▼
   USE    IGNORE

The original project specification explicitly established this protection against accidentally using an unrelated channel.

7. Cloud Initialization

After Telegram login:

Telegram connected
        ↓
Find LumoVault Backup
        ↓
 ┌───────────────┐
 │ Exists?       │
 └───────┬───────┘
         │
   ┌─────┴─────┐
   ▼           ▼
 YES           NO
   │           │
   ▼           ▼
Validate      Create
   │           │
   └─────┬─────┘
         ▼
      Save chatId
         ↓
     Scan history
         ↓
    Build Cloud index
         ↓
      Cloud screen

LumoVault must save the channel/chat ID locally after successful discovery or creation.

On subsequent launches:

Saved chatId
     ↓
Validate
     ↓
Use directly

Rediscovery is required if:

channel was deleted

Telegram account changed

stored chat ID becomes invalid

channel no longer satisfies LumoVault validation

8. Cloud Library

The Cloud screen represents the remote LumoVault library.

It should show media stored remotely even if the corresponding local file no longer exists.

Example:

Phone

IMG_001.jpg   ✓
IMG_002.jpg   ✓
IMG_003.jpg   deleted


Cloud

IMG_001.jpg
IMG_002.jpg
IMG_003.jpg

IMG_003.jpg remains visible because its original exists remotely.

The Cloud library should behave like a real photo library, not like a Telegram message list.

9. Cloud Index / Manifest

LumoVault needs a remote/local index connecting local media and Telegram messages.

Core metadata:

mediaId
hash
fileName
mimeType
size
dateTaken
latitude
longitude
telegramMessageId
thumbnail/preview information
backupState

The index enables:

Cloud browsing

duplicate detection

backup recognition

restore association

local/cloud relationship

remote verification

deleted-local-media visibility

The original specification established this remote manifest/index as a core architectural requirement.

10. Intelligent Backup Recognition

This is one of the most important parts of LumoVault.

LumoVault must know whether a local media item is already backed up.

It must never blindly upload the same media repeatedly.

Each local media item should have a backup record.

Conceptually:

MediaItem
├── localUri
├── MediaStore ID
├── fileName
├── size
├── mimeType
├── dateTaken
├── hash
├── telegramMessageId
├── uploadState
└── uploadedAt

The original design specifically established content hashing, local backup records, Telegram message association, and upload state.

11. Duplicate Detection

The primary exact-content identifier is a content hash.

Example:

Photo
  ↓
SHA-256
  ↓
ABC123...
  ↓
Local DB
  ↓
Remote manifest

If the hash already exists remotely:

Already backed up
        ↓
Do not upload again

If it does not exist:

Not backed up
      ↓
Upload

12. Efficient Media Recognition

LumoVault must not calculate expensive full-file hashes for every item every time the gallery scans.

Use a layered approach.

Fast checks
├── MediaStore ID
├── file size
├── modification timestamp
└── metadata
       ↓
Potential match?
       ↓
Background SHA-256
       ↓
Exact confirmation

This keeps gallery scanning responsive while allowing reliable backup verification.

The original specification explicitly called for this layered approach, particularly for large videos.

13. Backup Status

Every local media item should have a visible but subtle backup state.

Suggested states:

☁  Not backed up / waiting
↑  Uploading
✓  Backed up
↻  Backup failed

The indicator should not dominate the gallery UI.

Example:

┌───────────────┐
│               │
│     PHOTO     │
│               │
│            ✓  │
└───────────────┘

Opening a media item should provide more detailed status.

Example:

September 24, 2026 · 6:42 PM

✓ Backed up

Original
4032 × 3024
4.2 MB
JPEG

Backed up today at 7:28 PM

[ Backup again ]

For unbacked media:

○ Not backed up

4.2 MB
JPEG

[ Back up ]

14. Reinstallation Recovery

LumoVault must account for this scenario:

User backs up 1,000 photos
        ↓
Uninstalls LumoVault
        ↓
Reinstalls LumoVault
        ↓
Logs into Telegram
        ↓
LumoVault scans device
        ↓
LumoVault scans cloud manifest
        ↓
Recognizes existing media
        ↓
Does NOT upload 1,000 duplicates

This is a core requirement.

15. Photos Screen

The Photos screen is the primary local library.

It should show:

local photos

local videos

local GIFs

chronological timeline

thumbnails

backup indicators

date grouping

fast scrolling

The design direction is inspired by modern photo-library applications, while remaining a native LumoVault interface.

16. Photos Timeline

The Photos screen should organize media chronologically.

Example:

September 24, 2026

[photo][photo][video]
[photo][GIF]  [photo]

September 23, 2026

[photo][photo][photo]
[video][photo][GIF]

The exact grid should remain responsive to different screen sizes.

The original overview specified a Google Photos-style mixed/asymmetric gallery direction.

17. Date Navigation

The Photos screen should provide fast chronological navigation.

Potential UI:

          September 2026
                │
[photos photos photos]
[photos photos photos]

                       2026
                       │
                       │
                       ●
                       │
                       │

A floating date indicator may appear during scrolling.

The user should be able to rapidly move through years/months.

18. Local Media Scanner

LumoVault uses Android's media system to discover local media.

The scanner must detect:

images

videos

GIFs

date information

MIME type

dimensions

file size

URI

modification timestamp

available EXIF metadata

GPS metadata

Android's MediaStore provides the system media index for shared media collections.

The scanner must avoid unnecessarily reading complete large files during ordinary gallery scanning.

19. Albums

Albums provide organization beyond chronological Photos.

Initial album capabilities:

view albums

create album

rename album

add/remove media

delete album

album cover

album media count

Future album improvements may include:

better album management

smart albums

automatic organization

20. Favorites

Favorites are a core organization feature.

Users should be able to:

favorite media

unfavorite media

view Favorites

favorite local media

maintain favorite state independently from backup state

Favorites should not cause duplicate cloud uploads.

21. Archive

Archive removes media from the main Photos timeline without deleting the underlying media.

Archived items remain accessible through Archive.

Archive state is separate from:

backup state

favorite state

trash state

22. Trash

Trash provides recovery rather than immediate permanent deletion.

Requirements:

move media to Trash

view Trash

restore media

permanently delete

clearly communicate destructive actions

distinguish local deletion from cloud deletion

Cloud media must not accidentally be deleted simply because a local copy was removed.

23. Recently Added

Recently Added provides a focused view of newly discovered media.

This is particularly useful after:

installing LumoVault

importing media

receiving new photos

scanning the device

restoring media

24. Cloud Screen

The Cloud screen is a primary screen.

Navigation direction:

Photos | Albums | Cloud | Map

Settings remains accessible from the top-right.

The Cloud screen should show:

remote photos

remote videos

remote GIFs

cloud-only media

local + cloud media

dates

thumbnails/previews

media type

remote backup status where useful

The original project explicitly established Cloud as a first-class screen because it serves a fundamentally different purpose from local Photos.

25. Cloud Screen — No Automatic Original Downloads

The Cloud screen must not download all original files to the device.

Instead:

Telegram remote metadata
        ↓
Cloud index
        ↓
Preview/thumbnail
        ↓
Cloud UI

When the user requests the original:

Cloud media
     ↓
User chooses download
     ↓
Download original
     ↓
Save locally

This is essential to the Cloud screen's purpose.

26. Cloud Media Viewer

Opening a Cloud item should first use a remote preview where possible.

The original remains remote.

The user can explicitly request:

[ Download to device ]

The app should communicate clearly when an original is not stored locally.

27. Media Viewer

The media viewer should support:

photos

videos

GIFs

zoom

swipe navigation

metadata

backup status

download/restore

manual backup

favorite

archive

trash

map/location access where available

28. Photo Metadata

Where available, the viewer should display:

date taken

time taken

location

camera/device

dimensions

file size

MIME/file type

backup state

Example:

September 24, 2026 · 6:42 PM

✓ Backed up

4032 × 3024
4.2 MB
JPEG

Camera
Device name

Location
Dinajpur, Bangladesh

Metadata availability must be handled gracefully.

Never invent metadata.

29. Interactive Map

The Map screen is a major LumoVault feature.

It should use an OpenStreetMap-based interactive map.

Core features:

map pan

zoom

GPS photo clusters

photo previews

cluster counts

tap cluster

tap photo

open media viewer

location-aware browsing

30. Photo Location

LumoVault should primarily obtain photo location from available media metadata/EXIF.

The app should not require continuous device location permission merely to display location information already contained in photos.

The original specification explicitly separated EXIF-based photo location from requiring live location access.

31. Map Clustering

When many photos share a geographic area:

        Map

        ┌───────┐
        │  128  │
        └───────┘

Zooming in should progressively reveal more precise clusters/media.

Selecting a cluster should show associated photos.

32. Onboarding

The final onboarding consists of 6 main screens.

1. Welcome
      ↓
2. How LumoVault Works
      ↓
3. Connect Telegram
      ↓
4. Permissions & Backup Setup
      ↓
5. Choose What to Back Up
      ↓
6. You're Ready
      ↓
Photos

This replaces the previous 8-screen version by combining:

Media permission

Notifications

Background backup

into one screen.

33. Onboarding Screen 1 — Welcome

Content:

LumoVault

Your photos.
Your storage.

Private photo, video & GIF backup
using your Telegram account.

[ Get Started ]

Purpose:

introduce LumoVault

establish the product concept

begin setup

34. Onboarding Screen 2 — How LumoVault Works

Show:

📱 Your phone
       ↓
   LumoVault
       ↓
☁️ LumoVault Backup
   on Telegram

Explain:

local media stays on the phone

backups are stored in Telegram

Cloud lets the user browse remote media

originals are downloaded only when requested

Button:

[ Continue ]

35. Onboarding Screen 3 — Connect Telegram

Purpose:

Authenticate the user's Telegram account.

UI:

Connect Telegram

Connect your Telegram account
to use it as your private cloud storage.

┌─────────────────────────────┐
│ 🇧🇩  +880        ▼          │
└─────────────────────────────┘

┌─────────────────────────────┐
│ Phone number                │
└─────────────────────────────┘

[ Continue ]

36. Country Code Selector

The country-code selector is a required feature.

It must be:

scrollable

searchable

easy to use

visually clean

touch friendly

Each entry should contain:

🇧🇩  Bangladesh       +880
🇺🇸  United States    +1
🇬🇧  United Kingdom   +44
...

When selected:

🇧🇩 +880

is shown next to the phone number field.

The list should support fast scrolling and search so users do not have to manually scroll through every country.

The selected country code and phone number must be handled as separate logical fields.

37. Telegram Authentication Flow

The authentication flow should support the required Telegram login sequence.

Conceptually:

Phone number
      ↓
Telegram authentication
      ↓
Verification code
      ↓
2FA password if enabled
      ↓
Authenticated
      ↓
Find/Create LumoVault Backup

Authentication errors must be shown clearly.

Examples:

invalid phone number

invalid code

expired code

Telegram network error

2FA required

account authorization failure

Never expose sensitive authentication information in logs.

38. Onboarding Screen 4 — Permissions & Backup Setup

This combines three former screens.

Title:

Set up LumoVault

The screen contains three sections/cards.

38.1 Media Access

Photos & Videos

Allow LumoVault to access your
photos, videos and GIFs so it can
find and back them up.

[ Allow Access ]

LumoVault should request only the media permissions required by the supported Android version and actual implementation. Android's media access rules differ by Android version, so implementation must follow the platform's current permission model rather than blindly using legacy storage permissions.

38.2 Notifications

Notifications

Get notified when backup starts,
finishes, or needs attention.

[ Allow Notifications ]
[ Not Now ]

Notification permission can remain optional where appropriate.

38.3 Background Backup

Background Backup

For reliable automatic backup,
LumoVault may need to run without
battery restrictions.

[ Check Settings ]
[ I'll Do This Later ]

This must not be treated as a fake Android permission.

It may open the appropriate system settings page.

39. Background Work

Background backup should use Android's supported background execution mechanisms.

WorkManager is appropriate for reliable deferrable background tasks, with constraints such as network availability when applicable.

Long-running upload operations may require an appropriate foreground-service strategy depending on Android requirements and the final implementation.

The exact execution strategy must be implemented according to the Android version targeted by the project.

40. Onboarding Screen 5 — Choose What to Back Up

Title:

What should LumoVault back up?

Options:

◉ All photos, videos & GIFs

○ Select folders

○ Not now

If the user selects folders:

Select folders

☑ Camera
☐ Screenshots
☐ Downloads
☑ WhatsApp Images
☐ Instagram

[ Continue ]

The folder selector is a sub-screen, not another main onboarding screen.

41. Onboarding Screen 6 — You're Ready

Show setup status:

You're ready

✓ Telegram connected
✓ Media access
✓ Notifications
✓ Backup source selected

LumoVault is ready to protect
your memories.

[ Start Backup ]

Then navigate to:

Photos

The original final onboarding state used this same confirmation concept.

42. Main Navigation

Primary navigation:

┌─────────────────────────────────────┐
│                                     │
│             Screen                  │
│                                     │
│                                     │
├─────────────────────────────────────┤
│ Photos │ Albums │ Cloud │ Map       │
└─────────────────────────────────────┘

Settings:

Top-right ⚙

The navigation must remain accessible and predictable.

43. Settings

Settings should include categories for:

Account

Telegram account

connection state

reconnect

sign out

Backup

automatic backup

backup sources

Wi-Fi/mobile data behavior

backup status

backup diagnostics

retry failed items

Cloud

LumoVault Backup channel

cloud verification

remote library information

Storage

local storage

free up space

cached previews

Appearance

dark theme

light theme

theme preference

Notifications

backup notifications

error notifications

About

LumoVault version

privacy information

open-source/licenses where applicable

44. Theme

Default theme:

Dark

dark blue

black

blue accents

high contrast

Material 3

Light

white

light blue

blue accents

The application should use Material 3 components and Compose-native layouts.

45. Typography

Use Android/Material typography.

Prioritize:

readability

hierarchy

clean spacing

consistent weights

accessible contrast

Avoid excessive decorative typography.

46. Icons

Use Material Symbols/icons where possible.

Icons should be:

recognizable

consistent

simple

appropriately sized

accessible with content descriptions where required

Avoid random icon styles mixed together.

47. Backup Engine

The backup engine is responsible for:

discovering media

determining backup state

creating upload queue

calculating hashes when needed

checking local DB

checking remote manifest

uploading missing media

associating Telegram message IDs

updating local state

verifying successful backup

retrying failures

reporting progress

Conceptual pipeline:

MediaStore
    ↓
Scanner
    ↓
Backup database
    ↓
Recognition
    ↓
Remote manifest
    ↓
Upload queue
    ↓
Telegram
    ↓
Verification
    ↓
Completed

48. Upload States

Each upload should have explicit state.

Example:

NOT_BACKED_UP
QUEUED
PREPARING
HASHING
UPLOADING
VERIFYING
BACKED_UP
FAILED
CANCELLED

The UI should map these internal states into simple user-facing states.

49. Retry Strategy

Temporary failures should not permanently mark a media item as failed.

Retry conditions may include:

network unavailable

Telegram temporary failure

server error

connection timeout

Use controlled retry/backoff.

Do not endlessly retry a permanently invalid item.

50. Backup Queue

The backup queue must support:

multiple media

progress

pause/cancel where feasible

retry

priority

persistence

restart recovery

If the application process dies, queued work should be recoverable.

51. Backup Verification

After upload:

Local original
      ↓
Hash
      ↓
Upload
      ↓
Telegram message
      ↓
Remote association
      ↓
Verification
      ↓
✓ Backed up

The database should not mark an item as fully backed up merely because an upload request started.

52. Restore

Restore must support cloud-only media.

Flow:

Cloud media
     ↓
User selects Download
     ↓
Download original
     ↓
Temporary storage
     ↓
Verify
     ↓
Save to device
     ↓
MediaStore scan
     ↓
Local library

After restoration, LumoVault should associate the restored local item with its cloud record where possible.

53. Free Up Space

The planned Free Up Space feature should identify local media that:

are safely backed up

have verified cloud copies

can potentially be removed locally

The app must clearly distinguish:

✓ Safely backed up

from:

☁ Exists remotely but verification incomplete

The user must explicitly approve local deletion.

Cloud copies must remain untouched.

54. Cloud-Only Media

Cloud-only media is a legitimate first-class state.

Example:

📱 —
☁️ ✓

It should remain visible in Cloud.

If the media is restored:

📱 ✓
☁️ ✓

55. Security

LumoVault must treat Telegram authentication/session data as sensitive.

Requirements:

do not log authentication codes

do not log Telegram passwords

do not expose session information in normal logs

avoid storing unnecessary sensitive information

protect local application state appropriately

use secure storage where credentials/secrets require it

56. Privacy

LumoVault's core privacy model:

User
 ↓
LumoVault
 ↓
User's Telegram account
 ↓
LumoVault Backup

The application should not introduce an unnecessary third-party cloud backend for storing the user's media.

The product should clearly explain that Telegram is being used as the remote storage mechanism.

57. Offline Behavior

When offline:

Should work

Photos

Albums

Favorites

Archive

Trash

local viewer

local metadata

local map data already available

Should gracefully degrade

Cloud

upload

remote previews

restore

remote verification

Example:

No internet

Photos
✓ Available

Cloud
○ Unable to refresh

Backup
○ Waiting for connection

58. Error Handling

Errors must be human-readable.

Avoid:

Exception: java.io.IOException...

Instead:

Backup couldn't finish

Telegram connection was interrupted.

[ Retry ]

For technical diagnostics, detailed information can be available through Backup Diagnostics.

59. Backup Health

Backup Health should communicate the actual state of the library.

Possible summary:

Backup Health

1,248 backed up
23 waiting
4 failed

Last backup
Today, 7:28 PM

[ View issues ]

It should not claim everything is safe if verification has not completed.

60. Diagnostics

Diagnostics can show:

total local media

backed-up media

cloud-only media

pending uploads

failed uploads

last successful backup

last scan

Telegram connection status

channel status

database status

This is especially useful for troubleshooting.

61. Database

The local database should represent at minimum:

Media

MediaEntity
- id
- localUri
- mediaStoreId
- fileName
- mimeType
- size
- dateTaken
- modifiedAt
- width
- height
- hash
- latitude
- longitude

Backup

BackupEntity
- mediaId
- hash
- telegramChatId
- telegramMessageId
- uploadState
- uploadedAt
- verifiedAt
- error

Albums

AlbumEntity
- id
- name
- coverMediaId
- createdAt

Album membership

AlbumMediaEntity
- albumId
- mediaId

User state

UserSettings
- theme
- onboardingCompleted
- backupEnabled
- notificationPreference
- sourceSelection

The exact database schema may be refined during implementation, but these concepts must remain represented.

62. Repository Architecture

Use clear separation between:

UI
 ↓
ViewModel
 ↓
Use Case / Domain
 ↓
Repository
 ↓
Data Source

Potential data sources:

MediaStore
Room
TDLib / Telegram
File system
WorkManager

UI must not directly manipulate Telegram or MediaStore.

63. Compose Architecture

Use screen-level ViewModels and observable UI state.

Compose should react to state rather than manually manipulating views.

The Android documentation recommends keeping ViewModels around screen-level composables and passing only required data/actions to child composables.

64. Navigation Architecture

Main destinations:

Photos
Albums
Cloud
Map

Secondary destinations:

Onboarding
Telegram authentication
Folder selection
Photo viewer
Cloud viewer
Album
Favorites
Archive
Trash
Settings
Backup diagnostics

Navigation must preserve state appropriately.

65. Performance Requirements

LumoVault must remain responsive when the device contains thousands of media items.

Requirements:

lazy loading

thumbnail loading

pagination where needed

database-backed queries

background scanning

background hashing

avoid loading full-resolution images into memory unnecessarily

avoid hashing every large file during every gallery refresh

avoid downloading Cloud originals unnecessarily

Compose is designed to support efficient declarative UI layouts, but the implementation must still avoid unnecessary work and memory usage.

66. Large Libraries

The app should be designed for:

hundreds of media

thousands of media

tens of thousands of media where device resources permit

The UI must not attempt to load the entire library into memory.

67. Media Thumbnails

Use appropriately sized thumbnails.

Do not decode 4K/8K originals just to display a small grid cell.

The viewer can load higher-resolution content when needed.

68. Cloud Thumbnail Strategy

Cloud browsing should prioritize:

metadata
+
remote preview

instead of:

download original

This is essential for reducing data usage and local storage consumption.

69. Automatic Backup

Automatic backup should:

detect new media

identify selected source folders

compare against backup records

queue new media

upload when conditions permit

verify

update state

Automatic backup must respect the user's selected source.

70. Manual Backup

The user should be able to manually back up:

one photo

multiple selected items

a video

a GIF

a folder/album where supported

Manual backup should show progress.

71. Changed Media

If an existing local media item changes/replaces content:

Old hash
   ↓
New hash
   ↓
Different content

LumoVault must not incorrectly assume the new content is already backed up.

The new content should be treated according to the backup recognition rules.

72. Deleted Local Media

Deleting a local copy should not automatically delete the cloud copy.

Example:

Local + Cloud
     ↓
Delete local
     ↓
Cloud only

This is an intentional feature of the Cloud library.

73. Account Changes

If the Telegram account changes:

Old account
   ↓
Disconnect
   ↓
New account
   ↓
Discover/create LumoVault Backup
   ↓
Build new cloud index

The app must not accidentally associate one Telegram account's cloud library with another account.

74. Channel Deletion

If LumoVault Backup is deleted:

LumoVault should detect that the stored chat ID is no longer valid.

It should clearly inform the user.

It should not silently create a new channel without appropriate confirmation when recovery/data continuity could be affected.

75. User Experience Rules

LumoVault should feel:

modern

calm

fast

trustworthy

private

clean

familiar

Avoid:

clutter

unnecessary dialogs

excessive animations

complicated technical terminology

Telegram-like chat UI

The user should feel like they are using a photo application.

76. Core Feature Set

The core LumoVault feature set includes:

Photos

Videos

GIFs

Cloud

Albums

Map

Telegram authentication

Automatic backup

Manual backup

Backup recognition

Duplicate prevention

Backup verification

Favorites

Archive

Trash/recovery

Recently Added

Free Up Space

Restore/download

Backup Health

Offline-first local gallery

Dark/light theme

Settings

77. Version 2 Features

The original roadmap also identified:

Locked Folder

Free up space

Recently Added

better album management

advanced restore

duplicate detection

backup diagnostics

improved map filters

basic photo editing

Some of these overlap with the core architecture and should be designed so they can be expanded without rewriting the foundation.

78. Future Features

Future/AI-oriented features are deliberately not required for the initial implementation.

Potential future features:

Search

OCR

People

Pets

object recognition

Memories

AI organization

smart albums

natural-language search

These should not delay the core backup/photo-library experience.

79. Explicitly Out of Initial Scope

Do not prioritize:

AI search

OCR

people recognition

object recognition

natural-language search

advanced editing

unnecessary social features

The first version should establish a reliable photo library and backup system.

80. Implementation Phases

The project will be implemented through 10 major prompts/phases.

Phase 1 — Project Foundation

Build:

Android project

Kotlin

Compose

Material 3

theme

navigation

architecture

base database

dependency structure

GitHub Actions

Phase 2 — Onboarding & Telegram Authentication

Build:

6-screen onboarding

country-code selector

Telegram phone authentication

verification

2FA

onboarding persistence

Telegram session state

Phase 3 — Local Photo Library

Build:

MediaStore scanner

Photos screen

timeline

thumbnails

videos

GIFs

metadata

local database

Phase 4 — Cloud / Telegram Library

Build:

channel discovery

channel validation

channel creation

remote scanning

cloud index

Cloud screen

remote previews

Phase 5 — Backup Engine

Build:

upload queue

photo upload

video upload

GIF upload

progress

retry

background processing

upload state

Phase 6 — Backup Recognition

Build:

hashes

local backup records

remote manifest

duplicate detection

verification

backup indicators

changed-media detection

reinstall recovery

Phase 7 — Organization

Build:

Albums

Favorites

Archive

Trash

Recently Added

organization UI

Phase 8 — Viewer & Map

Build:

photo viewer

video viewer

GIF viewer

metadata

GPS/EXIF

OSM map

clusters

map → viewer integration

Phase 9 — Restore / Free Up Space / Background

Build:

original download

restore

Free Up Space

automatic backup

WorkManager

foreground upload handling where required

backup health

diagnostics

Phase 10 — Polish & Testing

Build/fix:

performance

UI consistency

error states

permission edge cases

Telegram edge cases

large-library testing

upload failure recovery

restore testing

duplicate testing

reinstall testing

theme testing

accessibility

GitHub Actions APK

81. Testing Requirements

At minimum, test:

Authentication

valid phone

invalid phone

invalid code

expired code

2FA

network interruption

Media

empty library

one photo

thousands of photos

video

GIF

large file

unsupported/corrupt media

Backup

first upload

duplicate upload

interrupted upload

retry

offline

Telegram failure

app restart during upload

Reinstallation

Backup
↓
Uninstall
↓
Install
↓
Login
↓
Scan
↓
Verify no duplicate upload

Cloud

existing channel

no channel

invalid same-name channel

deleted channel

cloud-only media

remote preview

download original

Permissions

permission granted

denied

partially granted

revoked later

notification disabled

battery restrictions

82. Acceptance Criteria

LumoVault is not considered functionally complete until:

Local library

Photos appear correctly

Videos appear correctly

GIFs appear correctly

Timeline works

Viewer works

Metadata works where available

Telegram

Authentication works

Country-code selector works

Existing LumoVault channel is found

Invalid same-name channel is rejected

Missing channel can be created

Chat ID is persisted

Cloud

Existing cloud media is discoverable

Cloud screen does not download every original

Cloud-only media appears

Remote previews work where available

Original download works

Backup

Manual backup works

Automatic backup works

Duplicate detection works

Hash verification works

Upload state is accurate

Retry works

Failed uploads are visible

Reinstall does not blindly duplicate uploads

Organization

Albums

Favorites

Archive

Trash

Recently Added

Map

EXIF GPS extraction

map display

clustering

photo preview

viewer integration

83. Build Strategy

LumoVault must use cloud-based builds.

Do not require local Gradle compilation on the user's low-end PC.

Development workflow:

OpenCode / Z Code
       ↓
GitHub repository
       ↓
GitHub Actions
       ↓
Cloud build
       ↓
Debug APK artifact
       ↓
Download/install on Android device

The implementation prompts must explicitly respect this constraint.

84. Development Rule

Each implementation phase must:

inspect the existing project

understand existing code

avoid unnecessary rewrites

implement only its assigned phase

preserve previous functionality

update documentation where needed

add tests where practical

run validation through cloud/GitHub Actions

report exactly what changed

report any remaining issue

85. Dependency Rule

Do not add libraries merely because they are convenient.

Every dependency must have a clear purpose.

Prefer:

AndroidX

Jetpack

Kotlin

Compose

established libraries required by the architecture

Avoid unnecessary third-party dependencies.

86. Architecture Rule

No UI screen should directly:

access Telegram

query MediaStore extensively

manipulate database internals

perform large file uploads

calculate expensive hashes on the main thread

Use the appropriate repository/use-case/background layers.

87. Main Data Flow

The complete system is:

                    ┌───────────────┐
                    │   Android     │
                    │   MediaStore  │
                    └───────┬───────┘
                            │
                            ▼
                    ┌───────────────┐
                    │ Media Scanner │
                    └───────┬───────┘
                            │
                            ▼
                    ┌───────────────┐
                    │  Local Room   │
                    │   Database    │
                    └───────┬───────┘
                            │
                 ┌──────────┴──────────┐
                 │                     │
                 ▼                     ▼
          Photos Library          Backup Engine
                                       │
                                       ▼
                                Recognition/Hash
                                       │
                                       ▼
                                Remote Manifest
                                       │
                                       ▼
                                  Telegram
                                       │
                                       ▼
                              LumoVault Backup
                                       │
                                       ▼
                                  Cloud Index
                                       │
                                       ▼
                                  Cloud Screen

88. Local + Cloud Relationship

The core relationship must remain:

                 LumoVault
                     │
          ┌──────────┴──────────┐
          ▼                     ▼
       Photos                 Cloud
          │                     │
     Local media          Telegram media
          │                     │
          └──────────┬──────────┘
                     ▼
               Backup status

This is the fundamental product model.

89. Final Product Definition

LumoVault is not simply:

an app that uploads photos to Telegram.

It is:

a complete local + cloud photo library where Telegram acts as the user's remote storage layer.

The user should be able to:

Take photo
    ↓
LumoVault detects it
    ↓
Backup recognizes it
    ↓
Upload original
    ↓
Verify
    ↓
✓ Backed up

Later:

Delete local copy
    ↓
Cloud copy remains
    ↓
Cloud screen still shows it
    ↓
Download original if needed

And after reinstall:

Reinstall LumoVault
       ↓
Login Telegram
       ↓
Find LumoVault Backup
       ↓
Scan cloud
       ↓
Scan local media
       ↓
Match using backup records/hash
       ↓
Restore relationship
       ↓
No blind duplicate uploads

90. Product Identity

Name: LumoVault

Core identity:

Your photos. Your storage.

LumoVault should feel like a private, modern photo library whose cloud layer happens to be powered by the user's Telegram account.

The Telegram implementation should remain largely invisible to the everyday user experience.

91. Master Navigation Summary

ONBOARDING

Welcome
   ↓
How it works
   ↓
Connect Telegram
   ↓
Permissions & Backup Setup
   ↓
Backup Sources
   ↓
Ready
   ↓
Photos


MAIN APP

┌────────────────────────────────────┐
│ Photos                         ⚙   │
│                                    │
│          Photo Library              │
│                                    │
├────────────────────────────────────┤
│ Photos │ Albums │ Cloud │ Map      │
└────────────────────────────────────┘


SECONDARY

Photo Viewer
Video Viewer
GIF Viewer
Album
Favorites
Archive
Trash
Cloud Viewer
Settings
Backup Health
Diagnostics
Folder Selection

92. Master Success Definition

The first major release of LumoVault should succeed if a user can:

install LumoVault

complete the 6-screen onboarding

select their country code

authenticate Telegram

allow media access

choose backup sources

see their local Photos library

connect/find/create LumoVault Backup

see existing cloud media without downloading all originals

back up new photos

back up videos

back up GIFs

see backup status

avoid duplicate uploads

view cloud-only media

download originals when requested

organize media

view media on the map

use the app offline for local media

reinstall the app and reconnect their cloud library

That is the foundation on which all later LumoVault features should be built.

END OF MASTER PRD