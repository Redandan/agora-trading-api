#!/usr/bin/env perl
use strict;
use warnings;

my $mode = shift @ARGV // '';
die "usage: $0 <tables|implicit> <entity-source>...\n"
  unless $mode eq 'tables' || $mode eq 'implicit';

for my $path (@ARGV) {
  open my $source_file, '<', $path
    or die "cannot read entity source $path: $!\n";
  local $/;
  my $source = <$source_file>;
  close $source_file
    or die "cannot close entity source $path: $!\n";

  next unless $source =~ /\@Entity\b/;

  if ($source =~ /\@Table\s*\(\s*name\s*=\s*"([^"]+)"/s) {
    print "$1\n" if $mode eq 'tables';
  } elsif ($mode eq 'implicit') {
    print "$path\n";
  }
}
