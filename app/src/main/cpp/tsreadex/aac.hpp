#ifndef INCLUDE_AAC_HPP
#define INCLUDE_AAC_HPP

#include <stddef.h>
#include <stdint.h>
#include <vector>

namespace Aac
{
void TransmuxDualMono(std::vector<uint8_t> &destLeft, std::vector<uint8_t> &destRight, std::vector<uint8_t> &workspace, bool &isDualMono,
                      bool muxLeftToStereo, bool muxRightToStereo, const uint8_t *payload, size_t lenBytes);
void TransmuxMonoToStereo(std::vector<uint8_t> &dest, std::vector<uint8_t> &workspace, bool &isMono, const uint8_t *payload, size_t lenBytes);
}

#endif
