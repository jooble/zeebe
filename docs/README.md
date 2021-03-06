# Zeebe - Documentation

This module contains the source of the documentation that is published at [https://docs.zeebe.io/](https://docs.zeebe.io/).

## Building the documentation locally

The documentation is generated by [mdbook](https://github.com/rust-lang/mdBook).

1. Install the latest mdbook version, see installation instructions on [GitHub](https://github.com/rust-lang/mdBook#installation).
1. Install the link checking backend for mdbook, see installation instructions on [GitHub](https://github.com/Michael-F-Bryan/mdbook-linkcheck#getting-started).
1. Go to the `docs` folder in the Zeebe repository
1. Run the command `mdbook serve` to start the web server. It will generate the documentation on every change.
1. See the documentation at [http://localhost:3000](http://localhost:3000)


## Writing documentation

The documentation is written in [Markdown](https://guides.github.com/features/mastering-markdown).

The important folders/files are:

* `docs/src` - Markdown files grouped by sections + PNG images
    * `SUMMARY.md` - the table of content (updated manually)
* `docs/media-src` - BPMN files to create the images from
* [Google Drive](https://drive.google.com/drive/folders/1PSo7T8H14T6rs0y0leXiq2WySqx82Bcs) - sources of other graphics/diagrams

## Releasing the documentation

When a pull request is merged to `develop` then it generates the documentation and publish it to [https://stage.docs.zeebe.io/](https://stage.docs.zeebe.io/).

The documentation is usually published from the stage environment to [https://docs.zeebe.io/](https://docs.zeebe.io/) during the release. However, it can be manually triggered using the [Zeebe CI job](https://ci.zeebe.camunda.cloud/job/zeebe-docs/) if needed.
